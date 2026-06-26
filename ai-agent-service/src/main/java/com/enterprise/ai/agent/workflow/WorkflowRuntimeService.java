package com.enterprise.ai.agent.workflow;

import com.enterprise.ai.agent.agentscope.AgentRouter;
import com.enterprise.ai.agent.graph.GraphSpec;
import com.enterprise.ai.agent.model.AgentResult;
import com.enterprise.ai.agent.platform.control.context.runtime.RuntimeContextInjectionResult;
import com.enterprise.ai.agent.runtime.GraphRuntimeContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class WorkflowRuntimeService {

    private final AgentRouter agentRouter;
    private final WorkflowRuntimeGraphAdapter workflowRuntimeGraphAdapter;

    public AgentResult execute(WorkflowRuntimeRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("workflow runtime request is required");
        }
        AgentEntryEntity agent = requireAgent(request.getAgent());
        WorkflowDefinitionEntity workflow = requireWorkflow(request.getWorkflow());
        WorkflowVersionEntity version = request.getActiveVersion();
        if (version == null && !request.isAllowDraftFallback()) {
            throw new IllegalArgumentException("active workflow version is required");
        }

        Map<String, Object> metadata = runtimeMetadata(request, agent, workflow, version);
        WorkflowRuntimeGraphAdapter.RuntimeGraph runtimeGraph = workflowRuntimeGraphAdapter.toRuntimeGraph(
                agent,
                workflow,
                version,
                WorkflowRuntimeGraphAdapter.RuntimeContextOptions.builder()
                        .metadata(metadata)
                        .build());
        mergeRuntimeContextIntoGraph(runtimeGraph.runtimeContext(), request.getRuntimeContext());
        return agentRouter.executeByGraphSpec(
                runtimeGraph.graphSpec(),
                runtimeGraph.runtimeContext(),
                request.getSessionId(),
                userId(request.getPrincipal()),
                request.getMessage(),
                roles(request.getPrincipal()),
                metadata,
                request.getRuntimeContext());
    }

    public WorkflowRuntimeGraphAdapter.RuntimeGraph toRuntimeGraph(AgentEntryEntity agent,
                                                                      WorkflowDefinitionEntity workflow,
                                                                      WorkflowVersionEntity activeVersion,
                                                                      Map<String, Object> metadata) {
        return workflowRuntimeGraphAdapter.toRuntimeGraph(
                agent,
                workflow,
                activeVersion,
                WorkflowRuntimeGraphAdapter.RuntimeContextOptions.builder()
                        .metadata(metadata)
                        .build());
    }

    private Map<String, Object> runtimeMetadata(WorkflowRuntimeRequest request,
                                                AgentEntryEntity agent,
                                                WorkflowDefinitionEntity workflow,
                                                WorkflowVersionEntity version) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        if (request.getMetadata() != null) {
            metadata.putAll(request.getMetadata());
        }
        if (request.getPageContext() != null) {
            metadata.putAll(request.getPageContext());
        }
        metadata.put("entryAgentId", agent.getId());
        metadata.put("entryAgentKeySlug", agent.getKeySlug());
        metadata.put("resolvedWorkflowId", workflow.getId());
        metadata.put("workflowId", workflow.getId());
        metadata.put("workflowKeySlug", workflow.getKeySlug());
        metadata.put("workflowVersion", version == null ? "DRAFT" : version.getVersion());
        metadata.put("workflowVersionId", version == null ? null : version.getId());
        metadata.put("sourceType", version == null ? "WORKFLOW_DRAFT" : "WORKFLOW_VERSION");
        metadata.put("sourceId", workflow.getId());
        if (request.getTraceId() != null) {
            metadata.put("traceId", request.getTraceId());
        }
        if (request.getPrincipal() != null) {
            metadata.put("principal", request.getPrincipal());
        }
        return metadata;
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

    private String userId(Map<String, Object> principal) {
        if (principal == null) {
            return null;
        }
        return firstText(
                stringValue(principal.get("globalUserId")),
                stringValue(principal.get("externalUserId")),
                stringValue(principal.get("userId")));
    }

    private void mergeRuntimeContextIntoGraph(GraphRuntimeContext runtimeContext,
                                              RuntimeContextInjectionResult runtimeContextInjection) {
        if (runtimeContext == null || runtimeContextInjection == null || !runtimeContextInjection.isEnabled()) {
            return;
        }
        String prompt = runtimeContextInjection.getPromptSection();
        if (!StringUtils.hasText(prompt)) {
            return;
        }
        String existing = runtimeContext.getSystemPrompt();
        if (!StringUtils.hasText(existing)) {
            runtimeContext.setSystemPrompt(prompt);
            return;
        }
        runtimeContext.setSystemPrompt(existing + "\n\n" + prompt);
    }

    private List<String> roles(Map<String, Object> principal) {
        if (principal == null) {
            return List.of();
        }
        Object raw = principal.get("roles");
        if (raw instanceof List<?> list) {
            List<String> roles = new ArrayList<>();
            for (Object item : list) {
                String value = stringValue(item);
                if (StringUtils.hasText(value)) {
                    roles.add(value.trim());
                }
            }
            return List.copyOf(roles);
        }
        return List.of();
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
}
