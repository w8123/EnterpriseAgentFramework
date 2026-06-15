package com.enterprise.ai.agent.workflow;

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
 * Converts Workflow domain objects into GraphSpec-native runtime context.
 */
@Component
@RequiredArgsConstructor
public class WorkflowRuntimeGraphAdapter {

    private final ObjectMapper objectMapper;

    public GraphRuntimeContext toRuntimeContext(AgentEntryEntity entryAgent,
                                                WorkflowDefinitionEntity workflow,
                                                WorkflowVersionEntity activeVersion,
                                                RuntimeContextOptions options) {
        AgentEntryEntity requiredAgent = requireAgent(entryAgent);
        WorkflowDefinitionEntity requiredWorkflow = requireWorkflow(workflow);
        RuntimeContextOptions safeOptions = options == null ? RuntimeContextOptions.builder().build() : options;
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

    public RuntimeGraph toRuntimeGraph(AgentEntryEntity entryAgent,
                                       WorkflowDefinitionEntity workflow,
                                       WorkflowVersionEntity activeVersion,
                                       RuntimeContextOptions options) {
        return new RuntimeGraph(
                readGraphSpec(resolveGraphSpecJson(requireWorkflow(workflow), activeVersion)),
                toRuntimeContext(entryAgent, workflow, activeVersion, options));
    }

    public record RuntimeGraph(GraphSpec graphSpec, GraphRuntimeContext runtimeContext) {
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

    public GraphSpec readGraphSpecFromDraft(Map<String, Object> draft) {
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
                                                  RuntimeContextOptions options) {
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

    private String resolveIntentType(WorkflowDefinitionEntity workflow, RuntimeContextOptions options) {
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

    @Builder
    @Getter
    public static class RuntimeContextOptions {
        private AgentWorkflowBindingEntity binding;
        private Map<String, Object> metadata;
    }
}
