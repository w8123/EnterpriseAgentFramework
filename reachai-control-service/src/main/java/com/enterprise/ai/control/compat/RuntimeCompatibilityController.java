package com.enterprise.ai.control.compat;

import com.enterprise.ai.control.client.runtime.RuntimeProxyClient;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

@RestController
@RequiredArgsConstructor
public class RuntimeCompatibilityController {

    private final RuntimeProxyClient runtimeProxyClient;

    @PostMapping({"/api/agent/execute", "/api/runtime/agents/execute"})
    public ResponseEntity<Map<String, Object>> executeAgent(@RequestBody Map<String, Object> body) {
        return runtimeProxyClient.executeAgent(body);
    }

    @PostMapping({"/api/agent/execute/detailed", "/api/runtime/agents/execute/detailed"})
    public ResponseEntity<Map<String, Object>> executeAgentDetailed(@RequestBody Map<String, Object> body) {
        return runtimeProxyClient.executeAgentDetailed(body);
    }

    @GetMapping({"/api/agent/route-evaluation", "/api/runtime/agents/route-evaluation"})
    public ResponseEntity<Map<String, Object>> routeEvaluation(
            @RequestParam(defaultValue = "30") int days) {
        return runtimeProxyClient.routeEvaluation(days);
    }

    @GetMapping("/api/traces/{traceId}")
    public ResponseEntity<Map<String, Object>> getTrace(@PathVariable String traceId) {
        return runtimeProxyClient.getTrace(traceId);
    }

    @GetMapping("/api/traces/recent")
    public ResponseEntity<Map<String, Object>> listRecentTraces(
            @RequestParam(required = false) String userId,
            @RequestParam(defaultValue = "30") int days,
            @RequestParam(defaultValue = "20") int limit) {
        return runtimeProxyClient.listRecentTraces(userId, days, limit);
    }

    @GetMapping("/api/runops/traces/{traceId}")
    public ResponseEntity<Map<String, Object>> runOpsDetail(@PathVariable String traceId) {
        return runtimeProxyClient.runOpsDetail(traceId);
    }

    @GetMapping("/api/runops/traces/recent")
    public ResponseEntity<Map<String, Object>> runOpsRecent(
            @RequestParam(required = false) String userId,
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(defaultValue = "7") int days) {
        return runtimeProxyClient.runOpsRecent(userId, limit, days);
    }

    @GetMapping("/api/runops/diagnostics")
    public ResponseEntity<Map<String, Object>> runOpsDiagnostics(
            @RequestParam(required = false) String userId,
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(defaultValue = "7") int days) {
        return runtimeProxyClient.runOpsDiagnostics(userId, limit, days);
    }

    @GetMapping("/api/trace-center/guard-decisions")
    public ResponseEntity<Object> listGuardDecisions(
            @RequestParam(required = false) String traceId,
            @RequestParam(required = false) String decisionType,
            @RequestParam(required = false) String targetKind,
            @RequestParam(required = false) String targetName,
            @RequestParam(required = false) String decision,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(defaultValue = "200") int limit) {
        return runtimeProxyClient.listGuardDecisions(
                traceId, decisionType, targetKind, targetName, decision, from, to, limit);
    }

    @GetMapping("/api/runops/traces/{traceId}/compare/{candidateTraceId}")
    public ResponseEntity<Map<String, Object>> runOpsCompare(@PathVariable String traceId,
                                                             @PathVariable String candidateTraceId) {
        return runtimeProxyClient.runOpsCompare(traceId, candidateTraceId);
    }

    @PostMapping("/api/runops/traces/{traceId}/replay")
    public ResponseEntity<Map<String, Object>> runOpsReplay(@PathVariable String traceId,
                                                            @RequestBody Map<String, Object> body) {
        return runtimeProxyClient.runOpsReplay(traceId, body);
    }

    @PostMapping("/api/chat")
    public ResponseEntity<Object> chat(@RequestBody Map<String, Object> body) {
        return runtimeProxyClient.chat(body);
    }

    @PostMapping({"/api/v1/agents/{key}/chat", "/gateway/agents/{key}/chat"})
    public ResponseEntity<Object> gatewayAgentChat(@PathVariable String key,
                                                   @RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> runtimeBody = new LinkedHashMap<>();
        if (body != null) {
            runtimeBody.putAll(body);
        }
        runtimeBody.put("agentDefinitionId", key);
        runtimeBody.putIfAbsent("intentHint", "AGENT_GATEWAY_CHAT");
        ResponseEntity<Map<String, Object>> response = runtimeProxyClient.executeAgent(runtimeBody);
        return ResponseEntity.status(response.getStatusCode())
                .headers(response.getHeaders())
                .body(response.getBody());
    }

    @GetMapping("/gateway/catalog")
    public ResponseEntity<Object> gatewayCatalog(@RequestParam(required = false) Long projectId) {
        ResponseEntity<Object> response = runtimeProxyClient.listAgents(projectId, null, null);
        if (!response.getStatusCode().is2xxSuccessful()) {
            return response;
        }
        Map<String, Object> catalog = new LinkedHashMap<>();
        catalog.put("agents", toGatewayAgentItems(response.getBody()));
        catalog.put("capabilities", List.of());
        return ResponseEntity.status(response.getStatusCode())
                .headers(response.getHeaders())
                .body(catalog);
    }

    @PostMapping(value = "/api/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<String> chatStream(@RequestBody Map<String, Object> body) {
        return runtimeProxyClient.chatStream(body);
    }

    @DeleteMapping("/api/chat/session/{sessionId}")
    public ResponseEntity<Object> clearChatSession(@PathVariable String sessionId) {
        return runtimeProxyClient.clearChatSession(sessionId);
    }

    @GetMapping("/api/workflows")
    public ResponseEntity<Object> listWorkflows(
            @RequestParam(required = false) Long projectId,
            @RequestParam(required = false) String projectCode,
            @RequestParam(required = false) String workflowType,
            @RequestParam(required = false) String status) {
        return runtimeProxyClient.listWorkflows(projectId, projectCode, workflowType, status);
    }

    @PostMapping("/api/workflows")
    public ResponseEntity<Object> createWorkflow(@RequestBody Map<String, Object> body) {
        return runtimeProxyClient.createWorkflow(body);
    }

    @GetMapping("/api/workflows/{id}")
    public ResponseEntity<Object> getWorkflow(@PathVariable String id) {
        return runtimeProxyClient.getWorkflow(id);
    }

    @PutMapping("/api/workflows/{id}")
    public ResponseEntity<Object> updateWorkflow(@PathVariable String id,
                                                 @RequestBody Map<String, Object> body) {
        return runtimeProxyClient.updateWorkflow(id, body);
    }

    @DeleteMapping("/api/workflows/{id}")
    public ResponseEntity<Object> deleteWorkflow(@PathVariable String id) {
        return runtimeProxyClient.deleteWorkflow(id);
    }

    @GetMapping("/api/workflows/graph-node-types")
    public ResponseEntity<Object> graphNodeTypes() {
        return runtimeProxyClient.graphNodeTypes();
    }

    @PostMapping("/api/workflows/runtime-validation")
    public ResponseEntity<Object> validateWorkflowRuntime(@RequestBody Map<String, Object> body) {
        return runtimeProxyClient.validateWorkflowRuntime(body);
    }

    @GetMapping("/api/workflows/{id}/studio")
    public ResponseEntity<Object> workflowStudio(@PathVariable String id) {
        return runtimeProxyClient.workflowStudio(id);
    }

    @PutMapping("/api/workflows/{id}/studio")
    public ResponseEntity<Object> saveWorkflowStudio(@PathVariable String id,
                                                     @RequestBody Map<String, Object> body) {
        return runtimeProxyClient.saveWorkflowStudio(id, body);
    }

    @PostMapping("/api/workflows/studio/debug-node")
    public ResponseEntity<Object> debugWorkflowNode(@RequestBody Map<String, Object> body) {
        return runtimeProxyClient.debugWorkflowNode(body);
    }

    @PostMapping("/api/workflows/studio/debug-run")
    public ResponseEntity<Object> debugWorkflowRun(@RequestBody Map<String, Object> body) {
        return runtimeProxyClient.debugWorkflowRun(body);
    }

    @PostMapping("/api/workflows/studio/generate-draft")
    public ResponseEntity<Object> generateWorkflowStudioDraft(@RequestBody Map<String, Object> body) {
        return runtimeProxyClient.generateWorkflowStudioDraft(body);
    }

    @PostMapping("/api/workflows/studio/edit-draft")
    public ResponseEntity<Object> editWorkflowStudioDraft(@RequestBody Map<String, Object> body) {
        return runtimeProxyClient.editWorkflowStudioDraft(body);
    }

    @PostMapping("/api/workflows/ai-coding/workflows")
    public ResponseEntity<Object> createWorkflowAiCodingWorkflow(@RequestBody Map<String, Object> body) {
        return runtimeProxyClient.createWorkflowAiCodingWorkflow(body);
    }

    @GetMapping("/api/workflows/{workflowId}/ai-coding/context")
    public ResponseEntity<Object> workflowAiCodingContext(@PathVariable String workflowId) {
        return runtimeProxyClient.workflowAiCodingContext(workflowId);
    }

    @PostMapping("/api/workflows/{workflowId}/ai-coding/validate")
    public ResponseEntity<Object> validateWorkflowAiCoding(@PathVariable String workflowId,
                                                           @RequestBody Map<String, Object> body) {
        return runtimeProxyClient.validateWorkflowAiCoding(workflowId, body);
    }

    @PostMapping("/api/workflows/{workflowId}/ai-coding/patch")
    public ResponseEntity<Object> patchWorkflowAiCoding(@PathVariable String workflowId,
                                                        @RequestBody Map<String, Object> body) {
        return runtimeProxyClient.patchWorkflowAiCoding(workflowId, body);
    }

    @PostMapping("/api/workflows/{workflowId}/ai-coding/run")
    public ResponseEntity<Object> runWorkflowAiCoding(@PathVariable String workflowId,
                                                      @RequestBody Map<String, Object> body) {
        return runtimeProxyClient.runWorkflowAiCoding(workflowId, body);
    }

    @GetMapping("/api/workflows/{workflowId}/ai-coding/versions")
    public ResponseEntity<Object> workflowAiCodingVersions(@PathVariable String workflowId) {
        return runtimeProxyClient.workflowAiCodingVersions(workflowId);
    }

    @PostMapping("/api/workflows/{workflowId}/ai-coding/publish")
    public ResponseEntity<Object> publishWorkflowAiCoding(@PathVariable String workflowId,
                                                          @RequestBody Map<String, Object> body) {
        return runtimeProxyClient.publishWorkflowAiCoding(workflowId, body);
    }

    @GetMapping("/api/workflows/{workflowId}/ai-coding/runs")
    public ResponseEntity<Object> workflowAiCodingRuns(@PathVariable String workflowId,
                                                       @RequestParam(required = false) Integer limit,
                                                       @RequestParam(required = false) Integer days) {
        return runtimeProxyClient.workflowAiCodingRuns(workflowId, limit, days);
    }

    @GetMapping("/api/workflows/{workflowId}/ai-coding/runs/{traceId}")
    public ResponseEntity<Object> workflowAiCodingRunDetail(@PathVariable String workflowId,
                                                            @PathVariable String traceId) {
        return runtimeProxyClient.workflowAiCodingRunDetail(workflowId, traceId);
    }

    @GetMapping("/api/workflows/{workflowId}/ai-coding/page-assistant/catalog")
    public ResponseEntity<Object> workflowAiCodingPageAssistantCatalog(@PathVariable String workflowId) {
        return runtimeProxyClient.workflowAiCodingPageAssistantCatalog(workflowId);
    }

    @PostMapping("/api/workflows/{workflowId}/ai-coding/page-assistant/validate")
    public ResponseEntity<Object> validateWorkflowAiCodingPageAssistant(@PathVariable String workflowId,
                                                                        @RequestBody Map<String, Object> body) {
        return runtimeProxyClient.validateWorkflowAiCodingPageAssistant(workflowId, body);
    }

    @PostMapping("/api/workflows/{workflowId}/ai-coding/page-assistant/smoke-test")
    public ResponseEntity<Object> smokeTestWorkflowAiCodingPageAssistant(@PathVariable String workflowId,
                                                                         @RequestBody Map<String, Object> body) {
        return runtimeProxyClient.smokeTestWorkflowAiCodingPageAssistant(workflowId, body);
    }

    @GetMapping("/api/workflows/{workflowId}/versions")
    public ResponseEntity<Object> listWorkflowVersions(@PathVariable String workflowId) {
        return runtimeProxyClient.listWorkflowVersions(workflowId);
    }

    @PostMapping("/api/workflows/{workflowId}/versions")
    public ResponseEntity<Object> publishWorkflowVersion(@PathVariable String workflowId,
                                                        @RequestBody Map<String, Object> body) {
        return runtimeProxyClient.publishWorkflowVersion(workflowId, body);
    }

    @PostMapping("/api/workflows/{workflowId}/versions/publish")
    public ResponseEntity<Object> publishWorkflowVersionExplicit(@PathVariable String workflowId,
                                                                @RequestBody Map<String, Object> body) {
        return runtimeProxyClient.publishWorkflowVersionExplicit(workflowId, body);
    }

    @PostMapping("/api/workflows/{workflowId}/versions/validate")
    public ResponseEntity<Object> validateWorkflowVersion(@PathVariable String workflowId) {
        return runtimeProxyClient.validateWorkflowVersion(workflowId);
    }

    @PostMapping("/api/workflows/{workflowId}/versions/{versionId}/rollback")
    public ResponseEntity<Object> rollbackWorkflowVersion(@PathVariable String workflowId,
                                                         @PathVariable Long versionId,
                                                         @RequestBody(required = false) Map<String, Object> body) {
        return runtimeProxyClient.rollbackWorkflowVersion(workflowId, versionId, body);
    }

    @PostMapping("/api/workflows/{id}/page-assistant/bind")
    public ResponseEntity<Object> bindPageAssistantWorkflow(@PathVariable String id,
                                                            @RequestBody Map<String, Object> body) {
        return runtimeProxyClient.bindPageAssistantWorkflow(id, body);
    }

    @GetMapping({"/api/agent/workflow-credentials", "/api/workflows/credentials"})
    public ResponseEntity<Object> listWorkflowCredentials(
            @RequestParam(required = false) Long projectId,
            @RequestParam(required = false) String projectCode) {
        return runtimeProxyClient.listWorkflowCredentials(projectId, projectCode);
    }

    @PostMapping({"/api/agent/workflow-credentials", "/api/workflows/credentials"})
    public ResponseEntity<Object> createWorkflowCredential(@RequestBody Map<String, Object> body) {
        return runtimeProxyClient.createWorkflowCredential(body);
    }

    @PutMapping({"/api/agent/workflow-credentials/{id}", "/api/workflows/credentials/{id}"})
    public ResponseEntity<Object> updateWorkflowCredential(@PathVariable Long id,
                                                          @RequestBody Map<String, Object> body) {
        return runtimeProxyClient.updateWorkflowCredential(id, body);
    }

    @DeleteMapping({"/api/agent/workflow-credentials/{id}", "/api/workflows/credentials/{id}"})
    public ResponseEntity<Object> deleteWorkflowCredential(@PathVariable Long id) {
        return runtimeProxyClient.deleteWorkflowCredential(id);
    }

    @GetMapping({"/api/agent/evals/datasets", "/api/runtime/evals/datasets"})
    public ResponseEntity<Object> listEvalDatasets(@RequestParam(required = false) String agentId) {
        return runtimeProxyClient.listEvalDatasets(agentId);
    }

    @PostMapping({"/api/agent/evals/datasets", "/api/runtime/evals/datasets"})
    public ResponseEntity<Object> createEvalDataset(@RequestBody Map<String, Object> body) {
        return runtimeProxyClient.createEvalDataset(body);
    }

    @PostMapping({"/api/agent/evals/datasets/{datasetId}/cases/import",
            "/api/runtime/evals/datasets/{datasetId}/cases/import"})
    public ResponseEntity<Object> importEvalCases(@PathVariable Long datasetId,
                                                  @RequestBody Map<String, Object> body) {
        return runtimeProxyClient.importEvalCases(datasetId, body);
    }

    @GetMapping({"/api/agent/evals/datasets/{datasetId}/cases",
            "/api/runtime/evals/datasets/{datasetId}/cases"})
    public ResponseEntity<Object> listEvalCases(@PathVariable Long datasetId) {
        return runtimeProxyClient.listEvalCases(datasetId);
    }

    @PostMapping({"/api/agent/evals/runs", "/api/runtime/evals/runs"})
    public ResponseEntity<Object> startEvalRun(@RequestBody Map<String, Object> body) {
        return runtimeProxyClient.startEvalRun(body);
    }

    @GetMapping({"/api/agent/evals/runs/{runId}", "/api/runtime/evals/runs/{runId}"})
    public ResponseEntity<Object> getEvalRun(@PathVariable Long runId) {
        return runtimeProxyClient.getEvalRun(runId);
    }

    @GetMapping({"/api/agent/evals/runs/{runId}/results", "/api/runtime/evals/runs/{runId}/results"})
    public ResponseEntity<Object> listEvalRunResults(@PathVariable Long runId) {
        return runtimeProxyClient.listEvalRunResults(runId);
    }

    @GetMapping("/api/agents")
    public ResponseEntity<Object> listAgents(
            @RequestParam(required = false) Long projectId,
            @RequestParam(required = false) String projectCode,
            @RequestParam(required = false) String agentKind) {
        return runtimeProxyClient.listAgents(projectId, projectCode, agentKind);
    }

    @PostMapping("/api/agents")
    public ResponseEntity<Object> createAgent(@RequestBody Map<String, Object> body) {
        return runtimeProxyClient.createAgent(body);
    }

    @GetMapping("/api/agents/{id}")
    public ResponseEntity<Object> getAgent(@PathVariable String id) {
        return runtimeProxyClient.getAgent(id);
    }

    @PutMapping("/api/agents/{id}")
    public ResponseEntity<Object> updateAgent(@PathVariable String id,
                                              @RequestBody Map<String, Object> body) {
        return runtimeProxyClient.updateAgent(id, body);
    }

    @DeleteMapping("/api/agents/{id}")
    public ResponseEntity<Object> deleteAgent(@PathVariable String id) {
        return runtimeProxyClient.deleteAgent(id);
    }

    @GetMapping("/api/agents/{agentId}/workflow-bindings")
    public ResponseEntity<Object> listAgentWorkflowBindings(@PathVariable String agentId) {
        return runtimeProxyClient.listAgentWorkflowBindings(agentId);
    }

    @PostMapping("/api/agents/{agentId}/workflow-bindings")
    public ResponseEntity<Object> createAgentWorkflowBinding(@PathVariable String agentId,
                                                             @RequestBody Map<String, Object> body) {
        return runtimeProxyClient.createAgentWorkflowBinding(agentId, body);
    }

    @PutMapping("/api/agents/{agentId}/workflow-bindings/{bindingId}")
    public ResponseEntity<Object> updateAgentWorkflowBinding(@PathVariable String agentId,
                                                             @PathVariable Long bindingId,
                                                             @RequestBody Map<String, Object> body) {
        return runtimeProxyClient.updateAgentWorkflowBinding(agentId, bindingId, body);
    }

    @DeleteMapping("/api/agents/{agentId}/workflow-bindings/{bindingId}")
    public ResponseEntity<Object> deleteAgentWorkflowBinding(@PathVariable String agentId,
                                                             @PathVariable Long bindingId) {
        return runtimeProxyClient.deleteAgentWorkflowBinding(agentId, bindingId);
    }

    @PostMapping("/api/agents/{agentId}/workflow-bindings/resolve-preview")
    public ResponseEntity<Object> resolveAgentWorkflowBindingPreview(@PathVariable String agentId,
                                                                     @RequestBody Map<String, Object> body) {
        return runtimeProxyClient.resolveAgentWorkflowBindingPreview(agentId, body);
    }

    @GetMapping("/api/runtimes")
    public ResponseEntity<Object> listRuntimes() {
        return runtimeProxyClient.listRuntimes();
    }

    @PostMapping("/api/runtimes/embedded/dispatch")
    public ResponseEntity<Object> dispatchEmbeddedRuntime(@RequestBody Map<String, Object> body) {
        return runtimeProxyClient.dispatchEmbeddedRuntime(body);
    }

    @PostMapping("/api/runtime/tools/{qualifiedName}/execute")
    public ResponseEntity<Object> executeRuntimeTool(@PathVariable String qualifiedName,
                                                     @RequestBody Map<String, Object> body) {
        return runtimeProxyClient.executeRuntimeTool(qualifiedName, body);
    }

    @PostMapping("/api/runtime/compositions/{qualifiedName}/execute")
    public ResponseEntity<Object> executeRuntimeComposition(@PathVariable String qualifiedName,
                                                            @RequestBody Map<String, Object> body) {
        return runtimeProxyClient.executeRuntimeComposition(qualifiedName, body);
    }

    @PostMapping("/api/runtime/interactions/{sessionId}/resume")
    public ResponseEntity<Object> resumeRuntimeInteraction(@PathVariable String sessionId,
                                                           @RequestBody Map<String, Object> body) {
        return runtimeProxyClient.resumeRuntimeInteraction(sessionId, body);
    }

    @PostMapping("/api/runtime/debug-sessions")
    public ResponseEntity<Object> createRuntimeDebugSession(@RequestBody Map<String, Object> body) {
        return runtimeProxyClient.createRuntimeDebugSession(body);
    }

    @GetMapping("/api/runtime/debug-sessions/{sessionId}")
    public ResponseEntity<Object> getRuntimeDebugSession(@PathVariable String sessionId) {
        return runtimeProxyClient.getRuntimeDebugSession(sessionId);
    }

    @PostMapping("/api/runtime/debug-sessions/{sessionId}/submit")
    public ResponseEntity<Object> submitRuntimeDebugSession(@PathVariable String sessionId,
                                                            @RequestBody Map<String, Object> body) {
        return runtimeProxyClient.submitRuntimeDebugSession(sessionId, body);
    }

    @PostMapping("/api/runtime/debug-sessions/{sessionId}/cancel")
    public ResponseEntity<Object> cancelRuntimeDebugSession(@PathVariable String sessionId) {
        return runtimeProxyClient.cancelRuntimeDebugSession(sessionId);
    }

    @GetMapping({"/api/agent/interactions/human-approvals", "/api/runtime/interactions/human-approvals"})
    public ResponseEntity<Object> listHumanApprovals(@RequestParam(required = false) Long agentId,
                                                     @RequestParam(required = false) String userId,
                                                     @RequestParam(defaultValue = "50") int limit) {
        return runtimeProxyClient.listHumanApprovals(agentId, userId, limit);
    }

    @PostMapping({"/api/agent/interactions/human-approvals/{interactionId}/submit",
            "/api/runtime/interactions/human-approvals/{interactionId}/submit"})
    public ResponseEntity<Object> submitHumanApproval(@PathVariable String interactionId,
                                                      @RequestBody Map<String, Object> body) {
        return runtimeProxyClient.submitHumanApproval(interactionId, body);
    }

    @DeleteMapping({"/api/agent/interactions/human-approvals/{interactionId}",
            "/api/runtime/interactions/human-approvals/{interactionId}"})
    public ResponseEntity<Object> cancelHumanApproval(@PathVariable String interactionId,
                                                      @RequestParam(required = false) String userId) {
        return runtimeProxyClient.cancelHumanApproval(interactionId, userId);
    }

    private static List<Map<String, Object>> toGatewayAgentItems(Object runtimeBody) {
        List<Map<String, Object>> agents = new ArrayList<>();
        for (Object value : extractItems(runtimeBody)) {
            if (!(value instanceof Map<?, ?> source)) {
                continue;
            }
            if (!isGatewayAgentEnabled(source) || !isCatalogVisible(source.get("visibility"))) {
                continue;
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", source.get("id"));
            item.put("keySlug", source.get("keySlug"));
            item.put("name", source.get("name"));
            item.put("projectCode", source.get("projectCode"));
            item.put("visibility", source.get("visibility"));
            agents.add(item);
        }
        return agents;
    }

    private static Collection<?> extractItems(Object body) {
        if (body instanceof Collection<?> items) {
            return items;
        }
        if (body instanceof Map<?, ?> map) {
            for (String key : List.of("data", "records", "items", "agents")) {
                Object value = map.get(key);
                if (value instanceof Collection<?> items) {
                    return items;
                }
            }
        }
        return List.of();
    }

    private static boolean isGatewayAgentEnabled(Map<?, ?> agent) {
        Object enabled = agent.get("enabled");
        return enabled == null || Boolean.TRUE.equals(enabled)
                || "true".equalsIgnoreCase(String.valueOf(enabled));
    }

    private static boolean isCatalogVisible(Object visibility) {
        if (visibility == null || String.valueOf(visibility).isBlank()) {
            return true;
        }
        return !"PRIVATE".equalsIgnoreCase(String.valueOf(visibility).trim());
    }
}
