package com.enterprise.ai.runtime.execution;

import com.enterprise.ai.runtime.agent.RuntimeAgentEntryService;
import com.enterprise.ai.runtime.agent.RuntimeAgentEntryView;
import com.enterprise.ai.runtime.workflow.RuntimeAgentWorkflowBindingResolveRequest;
import com.enterprise.ai.runtime.workflow.RuntimeAgentWorkflowBindingService;
import com.enterprise.ai.runtime.workflow.RuntimeAgentWorkflowBindingView;
import com.enterprise.ai.runtime.workflow.RuntimeWorkflowDefinitionEntity;
import com.enterprise.ai.runtime.workflow.RuntimeWorkflowDefinitionService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class RuntimeAgentExecutionService {

    private final RuntimeAgentEntryService agentService;
    private final RuntimeAgentWorkflowBindingService bindingService;
    private final RuntimeWorkflowDefinitionService workflowService;
    private final RuntimeGraphSpecExecutor graphSpecExecutor;

    public Map<String, Object> execute(Map<String, Object> request, boolean detailed) {
        Map<String, Object> body = request == null ? Map.of() : request;
        String agentLookup = firstText(
                text(body.get("agentDefinitionId")),
                text(body.get("agentId")),
                text(body.get("keySlug")));
        if (!StringUtils.hasText(agentLookup)) {
            return error("RUNTIME_AGENT_REQUIRED", "agentDefinitionId is required", null, null, null, body, detailed);
        }

        Optional<RuntimeAgentEntryView> agent = agentService.findByIdOrKeySlug(agentLookup);
        if (agent.isEmpty()) {
            return error("RUNTIME_AGENT_NOT_FOUND", "Agent not found: " + agentLookup, null, null, null, body, detailed);
        }

        List<Map<String, Object>> steps = new ArrayList<>();
        RuntimeAgentEntryView agentView = agent.get();
        steps.add(step("resolve-agent", agentView.id()));
        RuntimeAgentWorkflowBindingResolveRequest bindingRequest = new RuntimeAgentWorkflowBindingResolveRequest(
                agentView.id(),
                firstText(text(body.get("projectCode")), agentView.projectCode()),
                text(body.get("pageKey")),
                text(body.get("route")),
                text(body.get("actionKey")),
                text(body.get("intentHint")));
        Optional<RuntimeAgentWorkflowBindingView> binding = bindingService.resolvePreview(bindingRequest);
        if (binding.isEmpty()) {
            return error("RUNTIME_AGENT_BINDING_NOT_FOUND",
                    "Agent workflow binding not found: " + agentView.id(),
                    agentView,
                    null,
                    steps,
                    body,
                    detailed);
        }

        RuntimeAgentWorkflowBindingView bindingView = binding.get();
        steps.add(step("resolve-binding", bindingView.workflowId()));
        Optional<RuntimeWorkflowDefinitionEntity> workflow = workflowService.findById(bindingView.workflowId());
        if (workflow.isEmpty()) {
            return error("RUNTIME_WORKFLOW_NOT_FOUND",
                    "Workflow not found: " + bindingView.workflowId(),
                    agentView,
                    bindingView,
                    steps,
                    body,
                    detailed);
        }

        RuntimeWorkflowDefinitionEntity workflowView = workflow.get();
        steps.add(step("resolve-workflow", firstText(workflowView.getKeySlug(), workflowView.getId())));
        if (!StringUtils.hasText(workflowView.getGraphSpecJson())) {
            return error("RUNTIME_WORKFLOW_GRAPH_MISSING",
                    "Workflow GraphSpec is missing: " + workflowView.getId(),
                    agentView,
                    bindingView,
                    steps,
                    body,
                    detailed);
        }

        RuntimeGraphSpecExecutionResult execution =
                graphSpecExecutor.execute(workflowView.getGraphSpecJson(), body);
        steps.addAll(execution.steps());
        return executionResponse(execution,
                agentView,
                bindingView,
                workflowView,
                steps,
                body,
                detailed);
    }

    private Map<String, Object> executionResponse(RuntimeGraphSpecExecutionResult execution,
                                                  RuntimeAgentEntryView agent,
                                                  RuntimeAgentWorkflowBindingView binding,
                                                  RuntimeWorkflowDefinitionEntity workflow,
                                                  List<Map<String, Object>> steps,
                                                  Map<String, Object> request,
                                                  boolean detailed) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("success", execution.success());
        response.put("answer", execution.answer());
        putIfPresent(response, "sessionId", text(request == null ? null : request.get("sessionId")));
        putIfPresent(response, "intentType", text(request == null ? null : request.get("intentHint")));
        if (detailed && steps != null && !steps.isEmpty()) {
            response.put("steps", steps);
        }
        Map<String, Object> metadata = metadata(execution.code(), agent, binding, workflow);
        if (execution.metadata() != null) {
            metadata.putAll(execution.metadata());
        }
        response.put("metadata", metadata);
        return response;
    }

    private Map<String, Object> error(String code,
                                      String answer,
                                      RuntimeAgentEntryView agent,
                                      RuntimeAgentWorkflowBindingView binding,
                                      List<Map<String, Object>> steps,
                                      Map<String, Object> request,
                                      boolean detailed) {
        return error(code, answer, agent, binding, null, steps, request, detailed);
    }

    private Map<String, Object> error(String code,
                                      String answer,
                                      RuntimeAgentEntryView agent,
                                      RuntimeAgentWorkflowBindingView binding,
                                      RuntimeWorkflowDefinitionEntity workflow,
                                      List<Map<String, Object>> steps,
                                      Map<String, Object> request,
                                      boolean detailed) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("success", false);
        response.put("answer", answer);
        putIfPresent(response, "sessionId", text(request == null ? null : request.get("sessionId")));
        putIfPresent(response, "intentType", text(request == null ? null : request.get("intentHint")));
        if (detailed && steps != null && !steps.isEmpty()) {
            response.put("steps", steps);
        }
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.putAll(metadata(code, agent, binding, workflow));
        response.put("metadata", metadata);
        return response;
    }

    private Map<String, Object> metadata(String code,
                                         RuntimeAgentEntryView agent,
                                         RuntimeAgentWorkflowBindingView binding,
                                         RuntimeWorkflowDefinitionEntity workflow) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("code", code);
        if (agent != null) {
            metadata.put("agentId", agent.id());
            metadata.put("agentKey", agent.keySlug());
            metadata.put("projectCode", agent.projectCode());
        }
        if (binding != null) {
            metadata.put("bindingId", binding.id());
            metadata.put("workflowId", binding.workflowId());
        }
        if (workflow != null) {
            metadata.put("workflowId", workflow.getId());
            metadata.put("workflowKey", workflow.getKeySlug());
            metadata.put("workflowStatus", workflow.getStatus());
            metadata.put("runtimeType", workflow.getRuntimeType());
        }
        return metadata;
    }

    private void putIfPresent(Map<String, Object> target, String key, Object value) {
        if (value != null) {
            target.put(key, value);
        }
    }

    private Map<String, Object> step(String name, String detail) {
        Map<String, Object> step = new LinkedHashMap<>();
        step.put("name", name);
        step.put("detail", detail);
        return step;
    }

    private String text(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value);
        return StringUtils.hasText(text) ? text.trim() : null;
    }

    private String firstText(String... values) {
        for (String value : values) {
            if (StringUtils.hasText(value)) {
                return value.trim();
            }
        }
        return null;
    }
}
