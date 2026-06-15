package com.enterprise.ai.agent.runtime;

import com.enterprise.ai.agent.workflow.AgentEntryEntity;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Transient runtime model for AgentScope / LangGraph4j execution.
 * Built from {@link AgentEntryEntity} and parsed {@code entryConfigJson}.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentRuntimeProfile {

    private String id;
    private String keySlug;
    private String name;
    private String description;
    private Long projectId;
    private String projectCode;
    private String visibility;
    @Builder.Default
    private List<String> allowedRoles = List.of();
    private String intentType;
    private String systemPrompt;
    @Builder.Default
    private List<String> tools = List.of();
    @Builder.Default
    private List<String> skills = List.of();
    private String modelInstanceId;
    @Builder.Default
    private String runtimeType = "AGENTSCOPE";
    @Builder.Default
    private String runtimePlacement = "CENTRAL";
    @Builder.Default
    private Map<String, Object> runtimeConfig = Map.of();
    @Builder.Default
    private Map<String, Object> defaultResourceConfig = Map.of();
    @Builder.Default
    private int maxSteps = 5;
    @Builder.Default
    private boolean enabled = true;
    @Builder.Default
    private String type = "single";
    private List<String> pipelineAgentIds;
    private String knowledgeBaseGroupId;
    @Builder.Default
    private boolean allowIrreversible = false;
    @Builder.Default
    private boolean useMultiAgentModel = false;
    private Map<String, Object> extra;

    public static AgentRuntimeProfile fromAgentEntry(AgentEntryEntity entry, ObjectMapper mapper) {
        if (entry == null) {
            throw new IllegalArgumentException("agent entry is required");
        }
        ObjectMapper om = mapper == null ? new ObjectMapper() : mapper;
        Map<String, Object> config = parseConfig(entry.getEntryConfigJson(), om);
        return AgentRuntimeProfile.builder()
                .id(entry.getId())
                .keySlug(entry.getKeySlug())
                .name(entry.getName())
                .description(entry.getDescription())
                .projectId(entry.getProjectId())
                .projectCode(entry.getProjectCode())
                .visibility(firstText(entry.getVisibility(), "PROJECT"))
                .allowedRoles(parseStringList(entry.getAllowedRolesJson(), om))
                .intentType(asString(config.get("intentType")))
                .systemPrompt(entry.getSystemPrompt())
                .tools(parseStringList(config.get("tools"), om))
                .skills(parseStringList(config.get("skills"), om))
                .modelInstanceId(entry.getModelInstanceId())
                .runtimeType(firstText(asString(config.get("runtimeType")), "AGENTSCOPE"))
                .runtimePlacement(firstText(asString(config.get("runtimePlacement")), "CENTRAL"))
                .runtimeConfig(parseMap(config.get("runtimeConfig"), om))
                .defaultResourceConfig(parseMap(config.get("defaultResourceConfig"), om))
                .maxSteps(asInt(config.get("maxSteps"), 5))
                .enabled(entry.getEnabled() == null || entry.getEnabled())
                .type(firstText(asString(config.get("type")), "single"))
                .pipelineAgentIds(parseStringList(config.get("pipelineAgentIds"), om))
                .knowledgeBaseGroupId(asString(config.get("knowledgeBaseGroupId")))
                .allowIrreversible(asBoolean(config.get("allowIrreversible"), false))
                .useMultiAgentModel(asBoolean(config.get("useMultiAgentModel"), false))
                .extra(parseMap(config.get("extra"), om))
                .build();
    }

    private static Map<String, Object> parseConfig(String json, ObjectMapper mapper) {
        if (!StringUtils.hasText(json)) {
            return Map.of();
        }
        try {
            return mapper.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (Exception ex) {
            return Map.of();
        }
    }

    private static List<String> parseStringList(Object raw, ObjectMapper mapper) {
        if (raw == null) {
            return List.of();
        }
        if (raw instanceof List<?> list) {
            List<String> out = new ArrayList<>();
            for (Object item : list) {
                if (item != null && StringUtils.hasText(String.valueOf(item))) {
                    out.add(String.valueOf(item).trim());
                }
            }
            return List.copyOf(out);
        }
        if (raw instanceof String text) {
            if (!StringUtils.hasText(text)) {
                return List.of();
            }
            try {
                List<String> parsed = mapper.readValue(text, new TypeReference<List<String>>() {});
                return parsed == null ? List.of() : List.copyOf(parsed);
            } catch (Exception ex) {
                return List.of();
            }
        }
        return List.of();
    }

    private static Map<String, Object> parseMap(Object raw, ObjectMapper mapper) {
        if (raw == null) {
            return Map.of();
        }
        if (raw instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            map.forEach((k, v) -> out.put(String.valueOf(k), v));
            return out.isEmpty() ? Map.of() : Collections.unmodifiableMap(out);
        }
        if (raw instanceof String text && StringUtils.hasText(text)) {
            try {
                Map<String, Object> parsed = mapper.readValue(text, new TypeReference<Map<String, Object>>() {});
                return parsed == null || parsed.isEmpty() ? Map.of() : parsed;
            } catch (Exception ex) {
                return Map.of();
            }
        }
        return Map.of();
    }

    private static String firstText(String... values) {
        for (String value : values) {
            if (StringUtils.hasText(value)) {
                return value.trim();
            }
        }
        return null;
    }

    private static String asString(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return StringUtils.hasText(text) ? text : null;
    }

    private static int asInt(Object value, int defaultValue) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value != null && StringUtils.hasText(String.valueOf(value))) {
            try {
                return Integer.parseInt(String.valueOf(value).trim());
            } catch (NumberFormatException ignored) {
                return defaultValue;
            }
        }
        return defaultValue;
    }

    private static boolean asBoolean(Object value, boolean defaultValue) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value != null && StringUtils.hasText(String.valueOf(value))) {
            return Boolean.parseBoolean(String.valueOf(value).trim());
        }
        return defaultValue;
    }
}
