package com.enterprise.ai.capability.catalog.httpapi;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.enterprise.ai.agent.capability.catalog.scan.ScanProjectEntity;
import com.enterprise.ai.agent.capability.catalog.scan.ScanProjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Human-entered HTTP API source (form or pasted curl). It is its own source kind, so an SDK sync
 * or a complete OpenAPI re-import never tombstones it; only an explicit removal does.
 *
 * <p>The request carries structure only. Credential values, base URLs and examples are never part
 * of it; the project connection supplies them at call time.</p>
 */
@Service
@RequiredArgsConstructor
public class ManualHttpApiService {
    static final String SOURCE_KEY_PREFIX = "manual:";
    private static final Pattern ROUTE_PARAMETER = Pattern.compile("\\{([A-Za-z][A-Za-z0-9_.-]*)}");
    private static final Set<String> METHODS = Set.of("GET", "POST", "PUT", "PATCH", "DELETE");
    private static final Set<String> SCALAR_TYPES = Set.of("string", "integer", "number", "boolean");
    private static final Set<String> ORIGINS = Set.of("form", "curl", "mcp");
    private static final int MAX_PARAMETERS = 50;

    private final ScanProjectMapper projects;
    private final HttpApiAssetService assets;
    private final HttpApiSourceBindingMapper bindings;
    private final HttpApiInventoryStateService inventories;
    private final ObjectMapper json;

    /** Creates the API, or updates the manual source of the same method + route. */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Result save(long projectId, Request request) {
        ScanProjectEntity project = project(projectId);
        HttpApiServiceScope scope = scope(project);
        if (request == null) throw new IllegalArgumentException("请填写 API 信息");
        String method = method(request.httpMethod());
        String path = path(request.path());
        String origin = StringUtils.hasText(request.origin()) ? request.origin().trim().toLowerCase(Locale.ROOT) : "form";
        if (!ORIGINS.contains(origin)) throw new IllegalArgumentException("来源类型无效");
        HttpApiOperationContract contract = new HttpApiOperationContract(method, project.getContextPath(), path,
                new HttpApiOperationContract.MappingConditions(List.of(), List.of(), List.of()),
                parameters(path, request.parameters()), requestBody(method, request.requestBody()),
                List.of(new HttpApiOperationContract.Response("200", responseSchema(request.responseType()),
                        List.of("application/json"))),
                authentication(request.authentication()), sideEffect(method, request.sideEffect()));
        String sourceKey = SOURCE_KEY_PREFIX + method + " " + path;
        if (sourceKey.length() > 256) throw new IllegalArgumentException("API 路径过长");
        HttpApiAssetService.ObserveRequest observe = new HttpApiAssetService.ObserveRequest(scope,
                HttpApiSourceKind.MANUAL, sourceKey, switch (origin) { case "curl" -> "粘贴 curl 添加"; case "mcp" -> "智能体通过 MCP 上传"; default -> "手动添加"; }, null,
                contract, request.businessGroups());
        var canonical = assets.validate(observe);
        observe = new HttpApiAssetService.ObserveRequest(scope, HttpApiSourceKind.MANUAL, sourceKey,
                observe.sourceLocation(), canonical.contractHash().substring(0, 16), contract, request.businessGroups());
        HttpApiAssetService.Observation observation = assets.observe(observe);
        inventories.recordSubset(scope, HttpApiSourceKind.MANUAL, List.of(observation.binding().getId()), List.of(), null);
        return new Result(observation.asset().getId(), observation.binding().getId(), observation.assetCreated(),
                observation.asset().getStatus());
    }

    /** Removes only a manual source of this project; other sources of the same API stay. */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public boolean remove(long projectId, long assetId) {
        ScanProjectEntity project = project(projectId);
        HttpApiServiceScope scope = scope(project);
        List<HttpApiSourceBindingEntity> manual = bindings.selectList(Wrappers.<HttpApiSourceBindingEntity>lambdaQuery()
                .eq(HttpApiSourceBindingEntity::getAssetId, assetId)
                .eq(HttpApiSourceBindingEntity::getProjectId, projectId)
                .eq(HttpApiSourceBindingEntity::getSourceKind, HttpApiSourceKind.MANUAL.name())
                .ne(HttpApiSourceBindingEntity::getStatus, HttpApiSourceBindingStatus.REMOVED.name()));
        boolean changed = false;
        for (HttpApiSourceBindingEntity binding : manual) {
            if (!scope.projectCode().equals(binding.getProjectCode()) || !scope.environment().equals(binding.getEnvironment())) continue;
            changed |= assets.remove(new HttpApiAssetService.RemoveRequest(scope, HttpApiSourceKind.MANUAL,
                    binding.getSourceKey())).changed();
        }
        return changed;
    }

    private ScanProjectEntity project(long projectId) {
        if (projectId <= 0) throw new IllegalArgumentException("项目无效");
        projects.lockCapabilityChanges(projectId);
        ScanProjectEntity project = projects.selectById(projectId);
        if (project == null) throw new NotFound();
        return project;
    }

    private HttpApiServiceScope scope(ScanProjectEntity project) {
        if (!StringUtils.hasText(project.getProjectCode())) throw new IllegalArgumentException("项目缺少项目编码，请先在项目管理里补齐");
        return new HttpApiServiceScope(project.getId(), project.getProjectCode(),
                StringUtils.hasText(project.getEnvironment()) ? project.getEnvironment().trim() : "default");
    }

    private String method(String raw) {
        String value = raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT);
        if (!METHODS.contains(value)) throw new IllegalArgumentException("请求方法只支持 GET/POST/PUT/PATCH/DELETE");
        return value;
    }

    private String path(String raw) {
        String value = raw == null ? "" : raw.trim();
        if (value.isEmpty()) throw new IllegalArgumentException("请填写 API 路径");
        if (value.matches("(?i)^https?://.*")) throw new IllegalArgumentException("API 路径不要带域名，只填 / 开头的路径");
        if (!value.startsWith("/")) value = "/" + value;
        value = value.replaceAll("/+", "/");
        if (value.length() > 1 && value.endsWith("/")) value = value.substring(0, value.length() - 1);
        if (value.length() > 512 || value.contains("?") || value.contains("#") || value.contains("%")
                || value.contains("\\") || value.chars().anyMatch(ch -> ch < 33 || ch == 127)) {
            throw new IllegalArgumentException("API 路径无效：不要包含查询参数、空格或转义字符");
        }
        for (String segment : value.split("/")) {
            if (".".equals(segment) || "..".equals(segment)) throw new IllegalArgumentException("API 路径不能包含相对路径段");
        }
        if (value.replaceAll(ROUTE_PARAMETER.pattern(), "").matches(".*[{}].*")) {
            throw new IllegalArgumentException("路径占位符写法无效，请用 {id} 这种形式");
        }
        return value;
    }

    private List<HttpApiOperationContract.Parameter> parameters(String path, List<Parameter> raw) {
        List<Parameter> input = raw == null ? List.of() : raw;
        if (input.size() > MAX_PARAMETERS) throw new IllegalArgumentException("参数最多 " + MAX_PARAMETERS + " 个");
        Set<String> routeNames = new LinkedHashSet<>();
        Matcher matcher = ROUTE_PARAMETER.matcher(path);
        while (matcher.find()) routeNames.add(matcher.group(1));
        List<HttpApiOperationContract.Parameter> result = new ArrayList<>();
        Set<String> declaredPath = new LinkedHashSet<>();
        for (Parameter parameter : input) {
            if (parameter == null || !StringUtils.hasText(parameter.name())) throw new IllegalArgumentException("参数名称不能为空");
            String name = parameter.name().trim();
            String location = parameter.location() == null ? "QUERY" : parameter.location().trim().toUpperCase(Locale.ROOT);
            if (!Set.of("PATH", "QUERY").contains(location)) throw new IllegalArgumentException("参数位置只支持路径参数或查询参数");
            String type = parameter.type() == null ? "string" : parameter.type().trim().toLowerCase(Locale.ROOT);
            if (!SCALAR_TYPES.contains(type)) throw new IllegalArgumentException("参数类型只支持 string/integer/number/boolean");
            ObjectNode schema = json.createObjectNode().put("type", type);
            if (StringUtils.hasText(parameter.description())) schema.put("description", trimmed(parameter.description(), 500));
            boolean pathParameter = "PATH".equals(location);
            if (pathParameter) declaredPath.add(name);
            result.add(new HttpApiOperationContract.Parameter(name, HttpApiParameterLocation.valueOf(location),
                    pathParameter || parameter.required(), schema, List.of()));
        }
        for (String name : routeNames) {
            if (!declaredPath.contains(name)) {
                declaredPath.add(name);
                result.add(new HttpApiOperationContract.Parameter(name, HttpApiParameterLocation.PATH, true,
                        json.createObjectNode().put("type", "string"), List.of()));
            }
        }
        if (!declaredPath.equals(routeNames)) throw new IllegalArgumentException("路径参数必须和路径里的 {占位符} 一一对应");
        return result;
    }

    private HttpApiOperationContract.RequestBody requestBody(String method, RequestBody raw) {
        if (raw == null || raw.schema() == null || raw.schema().isNull()) return null;
        if ("GET".equals(method)) throw new IllegalArgumentException("GET 请求不能带请求体");
        if (!raw.schema().isObject()) throw new IllegalArgumentException("请求体结构无效");
        String media = StringUtils.hasText(raw.contentType()) ? raw.contentType().trim().toLowerCase(Locale.ROOT) : "application/json";
        if (!Set.of("application/json", "text/plain").contains(media)) {
            throw new IllegalArgumentException("请求体只支持 application/json 或 text/plain");
        }
        String reason = com.enterprise.ai.common.capability.HttpApiJsonBinding.unsupportedReason(raw.schema());
        if (reason != null) throw new IllegalArgumentException(reason);
        return new HttpApiOperationContract.RequestBody(raw.required() == null || raw.required(), raw.schema(), List.of(media));
    }

    private JsonNode responseSchema(String raw) {
        if ("array".equalsIgnoreCase(raw == null ? "" : raw.trim())) {
            ObjectNode schema = json.createObjectNode().put("type", "array");
            schema.putObject("items").put("type", "object");
            return schema;
        }
        return json.createObjectNode().put("type", "object");
    }

    private HttpApiOperationContract.AuthenticationRequirement authentication(Authentication raw) {
        String mode = raw == null || !StringUtils.hasText(raw.mode()) ? "UNKNOWN" : raw.mode().trim().toUpperCase(Locale.ROOT);
        return switch (mode) {
            case "UNKNOWN" -> new HttpApiOperationContract.AuthenticationRequirement(false, "UNKNOWN", List.of(), List.of());
            case "NONE" -> new HttpApiOperationContract.AuthenticationRequirement(false, "NONE", List.of(), List.of());
            case "BEARER" -> new HttpApiOperationContract.AuthenticationRequirement(true, "REQUIRED",
                    List.of("HTTP:bearer:manual"), List.of("Authorization"));
            case "API_KEY_HEADER" -> {
                String header = raw.headerName() == null ? "" : raw.headerName().trim();
                if (!header.matches("[A-Za-z0-9!#$%&'*+.^_`|~-]{1,128}")) throw new IllegalArgumentException("API Key 请求头名称无效");
                yield new HttpApiOperationContract.AuthenticationRequirement(true, "REQUIRED",
                        List.of("API_KEY:manual"), List.of(header));
            }
            default -> throw new IllegalArgumentException("认证方式无效");
        };
    }

    private String sideEffect(String method, String raw) {
        if (StringUtils.hasText(raw)) {
            String value = raw.trim().toUpperCase(Locale.ROOT);
            if (!Set.of("READ_ONLY", "IDEMPOTENT_WRITE", "WRITE", "IRREVERSIBLE").contains(value)) {
                throw new IllegalArgumentException("操作性质无效");
            }
            return value;
        }
        return "GET".equals(method) ? "READ_ONLY" : "WRITE";
    }

    private String trimmed(String value, int max) {
        String text = value.trim();
        return text.length() > max ? text.substring(0, max) : text;
    }

    public record Request(String httpMethod, String path, String sideEffect, String origin,
                          List<Parameter> parameters, RequestBody requestBody, String responseType,
                          Authentication authentication, List<String> businessGroups) { }

    public record Parameter(String name, String location, String type, boolean required, String description) { }

    public record RequestBody(String contentType, Boolean required, JsonNode schema) { }

    public record Authentication(String mode, String headerName) { }

    public record Result(Long apiId, Long sourceId, boolean created, String status) { }

    public static class NotFound extends RuntimeException { }
}
