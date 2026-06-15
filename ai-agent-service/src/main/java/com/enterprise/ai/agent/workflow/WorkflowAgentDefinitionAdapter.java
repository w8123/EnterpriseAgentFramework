package com.enterprise.ai.agent.workflow;

import com.enterprise.ai.agent.agent.AgentDefinition;
import com.enterprise.ai.agent.graph.GraphSpec;
import com.enterprise.ai.agent.runtime.GraphRuntimeContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Builder;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 将 Workflow 领域对象转换为旧 Runtime 兼容用的 {@link AgentDefinition} shell。
 * <p>
 * {@link AgentDefinition} 是历史 Agent 产品定义，携带名称、prompt、tools、ACL 等旧编排语义，
 * 不是 Workflow 编排主模型。平台级运行语义核心是 {@link GraphSpec}；
 * 本 adapter 仅在调用 {@code AgentRouter}、{@code LangGraph4jRuntimeAdapter} 等
 * 仍依赖 AgentDefinition 的兼容边界使用。
 */
@Component
@RequiredArgsConstructor
public class WorkflowAgentDefinitionAdapter {

    private final ObjectMapper objectMapper;

    /**
     * 从 AgentEntry + Workflow + 可选发布版本构造 GraphSpec-native Runtime 上下文。
     */
    public GraphRuntimeContext toRuntimeContext(AgentEntryEntity entryAgent,
                                                WorkflowDefinitionEntity workflow,
                                                WorkflowVersionEntity activeVersion,
                                                RuntimeShellOptions options) {
        AgentEntryEntity requiredAgent = requireAgent(entryAgent);
        WorkflowDefinitionEntity requiredWorkflow = requireWorkflow(workflow);
        RuntimeShellOptions safeOptions = options == null ? RuntimeShellOptions.builder().build() : options;
        Map<String, Object> extra = buildRuntimeExtra(requiredAgent, requiredWorkflow, activeVersion, safeOptions);
        boolean draft = activeVersion == null;
        return GraphRuntimeContext.builder()
                .sourceType(draft ? "WORKFLOW_DRAFT" : "WORKFLOW_VERSION")
                .sourceId(requiredWorkflow.getId())
                .sourceKeySlug(requiredWorkflow.getKeySlug())
                .sourceVersion(draft ? "DRAFT" : activeVersion.getVersion())
                .sourceVersionId(draft ? null : activeVersion.getId())
                .name(requiredWorkflow.getName())
                .intentType(resolveIntentType(requiredWorkflow, safeOptions))
                .projectId(requiredWorkflow.getProjectId())
                .projectCode(firstText(requiredWorkflow.getProjectCode(), requiredAgent.getProjectCode()))
                .runtimeType(firstText(requiredWorkflow.getRuntimeType(), "LANGGRAPH4J"))
                .runtimePlacement("CENTRAL")
                .modelInstanceId(firstText(requiredWorkflow.getDefaultModelInstanceId(), requiredAgent.getModelInstanceId()))
                .systemPrompt(requiredAgent.getSystemPrompt())
                .canvasJson(resolveCanvasJson(requiredWorkflow, activeVersion))
                .extra(extra)
                .build();
    }

    /**
     * GraphSpec + Runtime 上下文组合，供 Workflow 生产执行路径使用。
     */
    public RuntimeGraph toRuntimeGraph(AgentEntryEntity entryAgent,
                                       WorkflowDefinitionEntity workflow,
                                       WorkflowVersionEntity activeVersion,
                                       RuntimeShellOptions options) {
        return new RuntimeGraph(
                readGraphSpec(resolveGraphSpecJson(requireWorkflow(workflow), activeVersion)),
                toRuntimeContext(entryAgent, workflow, activeVersion, options));
    }

    public record RuntimeGraph(GraphSpec graphSpec, GraphRuntimeContext runtimeContext) {
    }

    /**
     * 从 AgentEntry + Workflow + 可选发布版本构造旧 Runtime 兼容用的 {@link AgentDefinition} shell。
     */
    public AgentDefinition toRuntimeShell(AgentEntryEntity entryAgent,
                                          WorkflowDefinitionEntity workflow,
                                          WorkflowVersionEntity activeVersion,
                                          RuntimeShellOptions options) {
        AgentEntryEntity requiredAgent = requireAgent(entryAgent);
        WorkflowDefinitionEntity requiredWorkflow = requireWorkflow(workflow);
        RuntimeShellOptions safeOptions = options == null ? RuntimeShellOptions.builder().build() : options;

        String graphSpecJson = resolveGraphSpecJson(requiredWorkflow, activeVersion);
        String canvasJson = resolveCanvasJson(requiredWorkflow, activeVersion);
        GraphSpec graphSpec = readGraphSpec(graphSpecJson);

        Map<String, Object> extra = buildRuntimeExtra(requiredAgent, requiredWorkflow, activeVersion, safeOptions);

        return AgentDefinition.builder()
                .id(requiredWorkflow.getId())
                .keySlug(requiredWorkflow.getKeySlug())
                .name(requiredWorkflow.getName())
                .description(requiredWorkflow.getDescription())
                .agentMode("WORKFLOW")
                .projectId(requiredWorkflow.getProjectId())
                .projectCode(firstText(requiredWorkflow.getProjectCode(), requiredAgent.getProjectCode()))
                .visibility(firstText(requiredAgent.getVisibility(), "PROJECT"))
                .intentType(resolveIntentType(requiredWorkflow, safeOptions))
                .systemPrompt(requiredAgent.getSystemPrompt())
                .modelInstanceId(firstText(requiredWorkflow.getDefaultModelInstanceId(), requiredAgent.getModelInstanceId()))
                .runtimeType(firstText(requiredWorkflow.getRuntimeType(), "LANGGRAPH4J"))
                .runtimePlacement("CENTRAL")
                .graphSpec(graphSpec)
                .canvasJson(canvasJson)
                .maxSteps(5)
                .enabled(true)
                .type("single")
                .extra(extra)
                .build();
    }

    /**
     * 从 Workflow 定义 + GraphSpec 构造 Studio 节点/整图调试 shell。
     */
    public AgentDefinition toDebugShell(GraphSpec graphSpec, DebugShellOptions options) {
        if (graphSpec == null) {
            throw new IllegalArgumentException("workflow graphSpec is required");
        }
        DebugShellOptions safeOptions = options == null ? DebugShellOptions.builder().build() : options;
        Map<String, Object> extra = new LinkedHashMap<>();
        if (safeOptions.isWorkflowDebug()) {
            extra.put("workflowDebug", true);
        }
        if (StringUtils.hasText(safeOptions.getWorkflowId())) {
            extra.put("workflowId", safeOptions.getWorkflowId());
        }
        if (safeOptions.getWorkflowVersionId() != null) {
            extra.put("workflowVersionId", safeOptions.getWorkflowVersionId());
        }
        if (StringUtils.hasText(safeOptions.getWorkflowVersion())) {
            extra.put("workflowVersion", safeOptions.getWorkflowVersion());
        }

        return AgentDefinition.builder()
                .id(firstText(safeOptions.getWorkflowId(), "workflow-debug"))
                .keySlug(safeOptions.getWorkflowKeySlug())
                .name(firstText(safeOptions.getWorkflowName(), graphSpec.getName(), "Workflow Debug"))
                .projectId(safeOptions.getProjectId())
                .projectCode(safeOptions.getProjectCode())
                .agentMode("WORKFLOW")
                .intentType(firstText(safeOptions.getWorkflowType(), "WORKFLOW"))
                .runtimeType(firstText(safeOptions.getRuntimeType(), "LANGGRAPH4J"))
                .runtimePlacement("CENTRAL")
                .modelInstanceId(safeOptions.getModelInstanceId())
                .graphSpec(graphSpec)
                .canvasJson(safeOptions.getCanvasJson())
                .enabled(true)
                .type("single")
                .extra(extra.isEmpty() ? null : extra)
                .build();
    }

    /**
     * 从 Executable Debug Session 草稿 Map 构造 shell，支持 WORKFLOW_DRAFT / WORKFLOW_VERSION 语义。
     */
    public AgentDefinition toDebugShellFromDraft(String targetType, Map<String, Object> draft) {
        if (draft == null || draft.isEmpty()) {
            throw new IllegalArgumentException("workflow debug draft is required");
        }
        String normalizedTarget = nullToEmpty(targetType).trim().toUpperCase();
        if ("WORKFLOW_DRAFT".equals(normalizedTarget) || "WORKFLOW_VERSION".equals(normalizedTarget)) {
            GraphSpec graphSpec = readGraphSpecFromDraft(draft);
            return toDebugShell(graphSpec, DebugShellOptions.builder()
                    .workflowId(asString(draft.get("workflowId"), draft.get("id")))
                    .workflowKeySlug(asString(draft.get("workflowKeySlug"), draft.get("keySlug")))
                    .workflowName(asString(draft.get("workflowName"), draft.get("name")))
                    .workflowType(asString(draft.get("workflowType"), draft.get("intentType")))
                    .projectId(asLong(draft.get("projectId")))
                    .projectCode(asString(draft.get("projectCode")))
                    .runtimeType(asString(draft.get("runtimeType")))
                    .modelInstanceId(asString(draft.get("modelInstanceId")))
                    .canvasJson(asString(draft.get("canvasJson")))
                    .workflowVersion(asString(draft.get("workflowVersion")))
                    .workflowVersionId(asLong(draft.get("workflowVersionId")))
                    .workflowDebug(true)
                    .build());
        }
        AgentDefinition definition = objectMapper.convertValue(draft, AgentDefinition.class);
        if (definition.getGraphSpec() == null) {
            definition.setGraphSpec(readGraphSpecFromDraft(draft));
        }
        if (!StringUtils.hasText(definition.getId())) {
            definition.setId(firstText(asString(draft.get("workflowId")), "studio-debug-draft"));
        }
        if (!StringUtils.hasText(definition.getName())) {
            definition.setName(firstText(asString(draft.get("workflowName")), "Studio Debug Draft"));
        }
        return definition;
    }

    public String resolveGraphSpecJson(WorkflowDefinitionEntity workflow, WorkflowVersionEntity activeVersion) {
        return firstText(
                activeVersion == null ? null : activeVersion.getGraphSpecSnapshotJson(),
                requireWorkflow(workflow).getGraphSpecJson());
    }

    public String resolveCanvasJson(WorkflowDefinitionEntity workflow, WorkflowVersionEntity activeVersion) {
        return firstText(
                activeVersion == null ? null : activeVersion.getCanvasSnapshotJson(),
                requireWorkflow(workflow).getCanvasJson());
    }

    public GraphSpec readGraphSpec(String graphSpecJson) {
        if (!StringUtils.hasText(graphSpecJson)) {
            throw new IllegalArgumentException("workflow graphSpec is required");
        }
        try {
            return objectMapper.readValue(graphSpecJson, GraphSpec.class);
        } catch (Exception ex) {
            throw new IllegalArgumentException("workflow graphSpec is invalid: " + ex.getMessage(), ex);
        }
    }

    private GraphSpec readGraphSpecFromDraft(Map<String, Object> draft) {
        Object graphSpec = draft.get("graphSpec");
        if (graphSpec == null) {
            graphSpec = draft.get("graphSpecJson");
        }
        if (graphSpec instanceof String text) {
            return readGraphSpec(text);
        }
        if (graphSpec != null) {
            return objectMapper.convertValue(graphSpec, GraphSpec.class);
        }
        throw new IllegalArgumentException("workflow graphSpec is required");
    }

    private Map<String, Object> buildRuntimeExtra(AgentEntryEntity entryAgent,
                                                  WorkflowDefinitionEntity workflow,
                                                  WorkflowVersionEntity activeVersion,
                                                  RuntimeShellOptions options) {
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("entryAgentId", entryAgent.getId());
        extra.put("entryAgentKeySlug", entryAgent.getKeySlug());
        extra.put("workflowId", workflow.getId());
        extra.put("workflowKeySlug", workflow.getKeySlug());
        extra.put("workflowVersion", activeVersion == null ? "DRAFT" : activeVersion.getVersion());
        extra.put("workflowVersionId", activeVersion == null ? null : activeVersion.getId());

        AgentWorkflowBindingEntity binding = options.getBinding();
        if (binding != null) {
            extra.put("bindingId", binding.getId());
            extra.put("bindingType", binding.getBindingType());
        }
        Map<String, Object> metadata = options.getMetadata();
        if (metadata != null) {
            if (metadata.get("bindingId") != null) {
                extra.put("bindingId", metadata.get("bindingId"));
            }
            if (metadata.get("bindingType") != null) {
                extra.put("bindingType", metadata.get("bindingType"));
            }
        }
        return extra;
    }

    private String resolveIntentType(WorkflowDefinitionEntity workflow, RuntimeShellOptions options) {
        AgentWorkflowBindingEntity binding = options.getBinding();
        if (binding != null && StringUtils.hasText(binding.getIntentType())) {
            return binding.getIntentType().trim();
        }
        Map<String, Object> metadata = options.getMetadata();
        if (metadata != null && StringUtils.hasText(stringValue(metadata.get("intentType")))) {
            return stringValue(metadata.get("intentType")).trim();
        }
        return firstText(workflow.getWorkflowType(), "WORKFLOW");
    }

    private AgentEntryEntity requireAgent(AgentEntryEntity agent) {
        if (agent == null || !StringUtils.hasText(agent.getId())) {
            throw new IllegalArgumentException("agent entry is required");
        }
        return agent;
    }

    private WorkflowDefinitionEntity requireWorkflow(WorkflowDefinitionEntity workflow) {
        if (workflow == null || !StringUtils.hasText(workflow.getId())) {
            throw new IllegalArgumentException("workflow is required");
        }
        return workflow;
    }

    private String firstText(String... values) {
        for (String value : values) {
            if (StringUtils.hasText(value)) {
                return value.trim();
            }
        }
        return null;
    }

    private String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private String asString(Object... values) {
        for (Object value : values) {
            String text = stringValue(value);
            if (StringUtils.hasText(text)) {
                return text.trim();
            }
        }
        return null;
    }

    private Long asLong(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        String text = stringValue(value);
        if (!StringUtils.hasText(text)) {
            return null;
        }
        return Long.parseLong(text.trim());
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    @Builder
    @Getter
    public static class RuntimeShellOptions {
        private AgentWorkflowBindingEntity binding;
        private Map<String, Object> metadata;
    }

    @Builder
    @Getter
    public static class DebugShellOptions {
        private String workflowId;
        private String workflowKeySlug;
        private String workflowName;
        private String workflowType;
        private Long projectId;
        private String projectCode;
        private String runtimeType;
        private String modelInstanceId;
        private String canvasJson;
        private String workflowVersion;
        private Long workflowVersionId;
        private boolean workflowDebug;
    }
}
