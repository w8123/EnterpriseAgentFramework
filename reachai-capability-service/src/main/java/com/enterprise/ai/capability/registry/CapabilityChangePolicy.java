package com.enterprise.ai.capability.registry;

import com.enterprise.ai.agent.capability.catalog.scan.ScanProjectEntity;
import com.enterprise.ai.agent.capability.catalog.tool.definition.ToolDefinitionEntity;
import com.enterprise.ai.agent.capability.catalog.tool.definition.ToolDefinitionParameter;
import com.enterprise.ai.agent.registry.RegistryContracts.CapabilityRegistration;
import com.enterprise.ai.agent.registry.RegistryContracts.FieldDiff;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Deterministic catalog policy. Source clients cannot select or weaken this policy. */
@Component
public class CapabilityChangePolicy {
    public static final String VERSION = "capability-change-v1";
    private static final Set<String> CONTRACT_FIELDS = Set.of("httpMethod", "baseUrl", "contextPath",
            "endpointPath", "requestBodyType", "responseType", "sideEffect", "enabled", "parameters", "metadata");
    private final ObjectMapper mapper;

    public CapabilityChangePolicy(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public List<CapabilityRegistration> normalize(ScanProjectEntity project, List<CapabilityRegistration> input) {
        if (input == null || input.size() > 2000) {
            throw new IllegalArgumentException("能力同步必须提供完整清单，且不能超过 2000 项");
        }
        Set<String> names = new HashSet<>();
        Set<String> storageNames = new HashSet<>();
        List<CapabilityRegistration> result = new ArrayList<>();
        for (CapabilityRegistration item : input) {
            if (item == null || item.name() == null || !item.name().trim().matches("[A-Za-z0-9_.-]{1,192}")) {
                throw new IllegalArgumentException("能力稳定标识不能为空，且只能包含字母、数字、点、横线与下划线");
            }
            String name = item.name().trim();
            if (!names.add(name.toLowerCase(Locale.ROOT))) {
                throw new IllegalArgumentException("能力清单包含重复稳定标识: " + name);
            }
            String storageName = (project.getProjectCode() + "_" + name).replaceAll("[^A-Za-z0-9_]+", "_")
                    .replaceAll("_+", "_").toLowerCase(Locale.ROOT);
            if (!storageNames.add(storageName)) throw new IllegalArgumentException("能力调用标识冲突: " + name);
            String address = first(item.baseUrl(), project.getBaseUrl());
            if (address != null) {
                URI uri;
                try { uri = URI.create(address); }
                catch (IllegalArgumentException invalid) { throw new IllegalArgumentException("能力服务地址无效: " + name); }
                if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                        || uri.getHost() == null || uri.getUserInfo() != null || uri.getFragment() != null
                        || uri.getQuery() != null) {
                    throw new IllegalArgumentException("能力服务地址必须是无凭据的 HTTP(S) 地址: " + name);
                }
            }
            Map<String, Object> metadata = new LinkedHashMap<>(item.metadata() == null ? Map.of() : item.metadata());
            metadata.remove("sideEffect"); // Dedicated declaration is the single owner of this value.
            metadata.put("assetType", CapabilityAssetType.fromMetadata(metadata).name());
            result.add(new CapabilityRegistration(name, first(item.title(), name),
                    first(item.description(), item.title(), name), first(item.httpMethod(), "POST").toUpperCase(Locale.ROOT),
                    address, first(item.contextPath(), project.getContextPath()), text(item.endpointPath()),
                    text(item.requestBodyType()), text(item.responseType()), sideEffect(item.sideEffect()),
                    !Boolean.FALSE.equals(item.enabled()), parameters(item.parameters(), 0), metadata));
        }
        result.sort(Comparator.comparing(CapabilityRegistration::name));
        return List.copyOf(result);
    }

    private List<ToolDefinitionParameter> parameters(List<ToolDefinitionParameter> input, int depth) {
        if (input == null) return List.of();
        if (depth > 20 || input.size() > 1000) throw new IllegalArgumentException("能力参数结构过深或过大");
        Set<String> names = new HashSet<>();
        List<ToolDefinitionParameter> result = new ArrayList<>();
        for (ToolDefinitionParameter parameter : input) {
            if (parameter == null || text(parameter.name()) == null || !names.add(parameter.name().trim())) {
                throw new IllegalArgumentException("能力参数名称不能为空或重复");
            }
            result.add(new ToolDefinitionParameter(parameter.name().trim(), text(parameter.type()),
                    parameter.description(), parameter.required(), text(parameter.location()),
                    parameters(parameter.children(), depth + 1), parameter.metadata()));
        }
        result.sort(Comparator.comparing(ToolDefinitionParameter::name));
        return result;
    }

    public Decision decide(String changeType, CapabilityRegistration registration, List<FieldDiff> differences) {
        if ("UNCHANGED".equals(changeType)) return new Decision(true, "UNCHANGED", "定义一致，无需处理");
        if ("ADDED".equals(changeType) && registration != null
                && "READ_ONLY".equals(registration.sideEffect()) && complete(registration)) {
            return new Decision(true, "NEW_READ_ONLY", "完整的只读能力已自动纳入目录；调用权限与外部发布保持独立");
        }
        if ("CHANGED".equals(changeType) && !differences.isEmpty()
                && differences.stream().allMatch(diff -> "title".equals(diff.field()))) {
            return new Decision(true, "DISPLAY_NAME", "仅展示名称变化，系统自动更新");
        }
        if ("DELETED".equals(changeType)) return new Decision(false, "SOURCE_MISSING", "来源已停止提供该能力，请核对下线安排");
        if ("ADDED".equals(changeType)) return new Decision(false, "NEW_CAPABILITY", "请确认该能力的业务用途与读写行为后纳入目录");
        if (differences.stream().anyMatch(diff -> CONTRACT_FIELDS.contains(diff.field()))) {
            return new Decision(false, "CONTRACT_CHANGED", "调用契约变化，请结合引用证据确认调用方已适配");
        }
        return new Decision(false, "SEMANTICS_CHANGED", "能力说明变化可能影响模型选择，请确认业务含义");
    }

    private boolean complete(CapabilityRegistration registration) {
        return registration.baseUrl() != null && registration.endpointPath() != null
                && registration.endpointPath().startsWith("/")
                && Set.of("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS").contains(registration.httpMethod())
                && completeParameters(registration.parameters());
    }

    private boolean completeParameters(List<ToolDefinitionParameter> parameters) {
        if (parameters == null) return true;
        return parameters.stream().allMatch(parameter -> parameter.type() != null
                && completeParameters(parameter.children() == null ? List.of() : parameter.children()));
    }

    public void requireComplete(CapabilityRegistration registration) {
        if (registration == null || !complete(registration)) {
            throw new IllegalArgumentException("能力调用定义不完整，请在业务系统补全地址、路径和参数类型后重新同步");
        }
    }

    public String contractHash(CapabilityRegistration registration) {
        if (registration == null) return null;
        ObjectNode tree = mapper.valueToTree(registration);
        tree.remove(List.of("name", "title", "description"));
        return hash(tree);
    }

    public String contractHash(ToolDefinitionEntity tool) {
        if (tool == null) return null;
        ObjectNode tree = mapper.createObjectNode();
        tree.put("httpMethod", first(tool.getHttpMethod(), "POST").toUpperCase(Locale.ROOT));
        tree.put("baseUrl", text(tool.getBaseUrl()));
        tree.put("contextPath", text(tool.getContextPath()));
        tree.put("endpointPath", text(tool.getEndpointPath()));
        tree.put("requestBodyType", text(tool.getRequestBodyType()));
        tree.put("responseType", text(tool.getResponseType()));
        tree.put("sideEffect", sideEffect(tool.getSideEffect()));
        tree.put("enabled", Boolean.TRUE.equals(tool.getEnabled()));
        tree.set("parameters", parameterTree(tool.getParametersJson()));
        JsonNode metadata = json(tool.getCapabilityMetadataJson(), mapper.createObjectNode());
        if (metadata instanceof ObjectNode object) object.remove("sideEffect");
        tree.set("metadata", metadata);
        return hash(tree);
    }

    private JsonNode parameterTree(String value) {
        JsonNode tree = json(value, mapper.createArrayNode());
        if (tree.isObject()) return tree; // Non-SDK catalog entries can declare JSON Schema directly.
        if (!tree.isArray()) throw new IllegalArgumentException("能力参数定义无效");
        List<ToolDefinitionParameter> parameters = new ArrayList<>();
        tree.forEach(node -> parameters.add(mapper.convertValue(node, ToolDefinitionParameter.class)));
        return mapper.valueToTree(parameters(parameters, 0));
    }

    private JsonNode json(String value, JsonNode fallback) {
        if (value == null || value.isBlank() || "null".equals(value)) return fallback;
        try { return mapper.readTree(value); }
        catch (Exception invalid) { throw new IllegalArgumentException("能力定义 JSON 无效", invalid); }
    }

    public String hash(Object value) {
        try {
            byte[] canonical = canonical(mapper.valueToTree(value)).toString().getBytes(StandardCharsets.UTF_8);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical));
        } catch (Exception invalid) { throw new IllegalArgumentException("无法计算能力定义指纹", invalid); }
    }

    public boolean equalValue(Object left, Object right) {
        return Objects.equals(comparable(left), comparable(right));
    }

    private JsonNode comparable(Object value) {
        if (value instanceof String string && (string.startsWith("{") || string.startsWith("["))) {
            try { return canonical(mapper.readTree(string)); } catch (Exception ignored) { /* ordinary text */ }
        }
        return canonical(mapper.valueToTree(value));
    }

    private JsonNode canonical(JsonNode value) {
        if (value.isObject()) {
            ObjectNode result = mapper.createObjectNode();
            List<String> fields = new ArrayList<>();
            value.fieldNames().forEachRemaining(fields::add);
            fields.stream().sorted().forEach(field -> result.set(field, canonical(value.get(field))));
            return result;
        }
        if (value.isArray()) {
            ArrayNode result = mapper.createArrayNode();
            value.forEach(item -> result.add(canonical(item)));
            return result;
        }
        return value;
    }

    public Object mergeSdkMetadata(CapabilityRegistration registration) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        if (registration.metadata() != null) {
            metadata.putAll(registration.metadata());
        }
        if (StringUtils.hasText(registration.sideEffect())) {
            metadata.put("sideEffect", registration.sideEffect().trim());
        }
        return metadata.isEmpty() ? null : metadata;
    }

    public CapabilityAssetType assetType(CapabilityRegistration registration) {
        return CapabilityAssetType.fromMetadata(registration == null ? null : registration.metadata());
    }

    public static String sideEffect(String value) {
        String normalized = first(value, "WRITE").toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "READ", "READ_ONLY", "NONE" -> "READ_ONLY";
            case "IDEMPOTENT_WRITE" -> "IDEMPOTENT_WRITE";
            case "IRREVERSIBLE" -> "IRREVERSIBLE";
            default -> "WRITE";
        };
    }

    private static String first(String... values) {
        for (String value : values) if (text(value) != null) return value.trim();
        return null;
    }

    private static String text(String value) { return value == null || value.isBlank() ? null : value.trim(); }

    public record Decision(boolean automatic, String reasonCode, String reason) { }
}
