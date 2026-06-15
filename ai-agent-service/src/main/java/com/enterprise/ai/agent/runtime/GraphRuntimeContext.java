package com.enterprise.ai.agent.runtime;

import com.enterprise.ai.agent.agent.AgentDefinition;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 轻量 Runtime 上下文：执行 {@link com.enterprise.ai.agent.graph.GraphSpec} 时携带来源身份与运行参数。
 * 不是持久化模型，也不是统一产品对象。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GraphRuntimeContext {

    /** WORKFLOW_DRAFT / WORKFLOW_VERSION / AGENT_COMPAT / COMPOSITION_DRAFT */
    private String sourceType;

    private String sourceId;

    private String sourceKeySlug;

    private String sourceVersion;

    private Long sourceVersionId;

    private String name;

    private String intentType;

    private Long projectId;

    private String projectCode;

    private String runtimeType;

    @Builder.Default
    private String runtimePlacement = "CENTRAL";

    private String modelInstanceId;

    private String systemPrompt;

    private String canvasJson;

    @Builder.Default
    private boolean allowIrreversible = false;

    @Builder.Default
    private Map<String, Object> defaultResourceConfig = Map.of();

    @Builder.Default
    private Map<String, Object> runtimeConfig = Map.of();

    /** Workflow / Agent 兼容 trace 元数据，如 workflowId、entryAgentId。 */
    private Map<String, Object> extra;

    private Map<String, Object> metadata;

    public static GraphRuntimeContext fromAgentDefinition(AgentDefinition definition) {
        if (definition == null) {
            return null;
        }
        Map<String, Object> extra = definition.getExtra() == null
                ? null
                : new LinkedHashMap<>(definition.getExtra());
        String sourceType = resolveSourceTypeFromExtra(extra);
        return GraphRuntimeContext.builder()
                .sourceType(sourceType)
                .sourceId(definition.getId())
                .sourceKeySlug(definition.getKeySlug())
                .sourceVersion(readExtraString(extra, "workflowVersion", "__version"))
                .sourceVersionId(readExtraLong(extra, "workflowVersionId", "__versionId"))
                .name(definition.getName())
                .intentType(definition.getIntentType())
                .projectId(definition.getProjectId())
                .projectCode(definition.getProjectCode())
                .runtimeType(definition.getRuntimeType())
                .runtimePlacement(definition.getRuntimePlacement())
                .modelInstanceId(definition.getModelInstanceId())
                .systemPrompt(definition.getSystemPrompt())
                .canvasJson(definition.getCanvasJson())
                .allowIrreversible(definition.isAllowIrreversible())
                .defaultResourceConfig(copyMap(definition.getDefaultResourceConfig()))
                .runtimeConfig(copyMap(definition.getRuntimeConfig()))
                .extra(extra)
                .build();
    }

    public static GraphRuntimeContext fromWorkflowDraft(String targetType, Map<String, Object> draft) {
        if (draft == null || draft.isEmpty()) {
            throw new IllegalArgumentException("workflow debug draft is required");
        }
        String normalizedTarget = nullToEmpty(targetType).trim().toUpperCase();
        if (!"WORKFLOW_DRAFT".equals(normalizedTarget) && !"WORKFLOW_VERSION".equals(normalizedTarget)) {
            throw new IllegalArgumentException("unsupported workflow draft targetType: " + targetType);
        }
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("workflowDebug", true);
        putIfPresent(extra, "workflowId", firstText(asString(draft.get("workflowId")), asString(draft.get("id"))));
        putIfPresent(extra, "workflowKeySlug", asString(draft.get("workflowKeySlug"), draft.get("keySlug")));
        putIfPresent(extra, "workflowVersion", asString(draft.get("workflowVersion")));
        putIfPresent(extra, "workflowVersionId", draft.get("workflowVersionId"));
        putIfPresent(extra, "entryAgentId", asString(draft.get("entryAgentId")));

        return GraphRuntimeContext.builder()
                .sourceType(normalizedTarget)
                .sourceId(firstText(asString(draft.get("workflowId")), asString(draft.get("id"))))
                .sourceKeySlug(firstText(asString(draft.get("workflowKeySlug")), asString(draft.get("keySlug"))))
                .sourceVersion(asString(draft.get("workflowVersion")))
                .sourceVersionId(asLong(draft.get("workflowVersionId")))
                .name(firstText(asString(draft.get("workflowName")), asString(draft.get("name")), "Workflow Debug"))
                .intentType(firstText(asString(draft.get("workflowType")), asString(draft.get("intentType")), "WORKFLOW"))
                .projectId(asLong(draft.get("projectId")))
                .projectCode(asString(draft.get("projectCode")))
                .runtimeType(firstText(asString(draft.get("runtimeType")), "LANGGRAPH4J"))
                .runtimePlacement("CENTRAL")
                .modelInstanceId(asString(draft.get("modelInstanceId")))
                .canvasJson(asString(draft.get("canvasJson")))
                .extra(extra.isEmpty() ? null : extra)
                .build();
    }

    private static String resolveSourceTypeFromExtra(Map<String, Object> extra) {
        if (extra == null || !Boolean.TRUE.equals(extra.get("workflowDebug"))) {
            return "AGENT_COMPAT";
        }
        if (extra.get("workflowVersionId") != null || StringUtils.hasText(asString(extra.get("workflowVersion")))) {
            return "WORKFLOW_VERSION";
        }
        return "WORKFLOW_DRAFT";
    }

    private static String readExtraString(Map<String, Object> extra, String... keys) {
        if (extra == null) {
            return null;
        }
        for (String key : keys) {
            String value = asString(extra.get(key));
            if (StringUtils.hasText(value)) {
                return value;
            }
        }
        return null;
    }

    private static Long readExtraLong(Map<String, Object> extra, String... keys) {
        if (extra == null) {
            return null;
        }
        for (String key : keys) {
            Long value = asLong(extra.get(key));
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private static Map<String, Object> copyMap(Map<String, Object> source) {
        return source == null ? Map.of() : new LinkedHashMap<>(source);
    }

    private static void putIfPresent(Map<String, Object> target, String key, Object value) {
        if (value == null) {
            return;
        }
        if (value instanceof String text && !StringUtils.hasText(text)) {
            return;
        }
        target.put(key, value);
    }

    private static String firstText(String... values) {
        for (String value : values) {
            if (StringUtils.hasText(value)) {
                return value.trim();
            }
        }
        return null;
    }

    private static String asString(Object... values) {
        for (Object value : values) {
            if (value == null) {
                continue;
            }
            String text = String.valueOf(value).trim();
            if (StringUtils.hasText(text)) {
                return text;
            }
        }
        return null;
    }

    private static Long asLong(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        String text = String.valueOf(value).trim();
        if (!StringUtils.hasText(text)) {
            return null;
        }
        return Long.parseLong(text);
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
