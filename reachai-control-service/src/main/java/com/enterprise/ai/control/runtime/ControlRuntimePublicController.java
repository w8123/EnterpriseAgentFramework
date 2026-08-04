package com.enterprise.ai.control.runtime;

import com.enterprise.ai.control.aiassist.ControlAiCodingAccessGuard;
import com.enterprise.ai.control.client.runtime.RuntimeProxyClient;
import com.enterprise.ai.control.client.runtime.RuntimeTrustedAgentExecutionGateway;
import com.enterprise.ai.control.identity.PlatformBearerAuthService;
import com.enterprise.ai.control.identity.PlatformUserEntity;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@RestController
public class ControlRuntimePublicController {

    private final RuntimeProxyClient runtimeProxyClient;
    private final ControlAiCodingAccessGuard aiCodingAccessGuard;
    private final RuntimeAgentStreamProxy runtimeAgentStreamProxy;
    private final RuntimeTrustedAgentExecutionGateway trustedExecutionGateway;
    private final PlatformBearerAuthService bearerAuthService;

    ControlRuntimePublicController(RuntimeProxyClient runtimeProxyClient) {
        this(runtimeProxyClient, null, null, null, null);
    }

    public ControlRuntimePublicController(RuntimeProxyClient runtimeProxyClient,
                                          ControlAiCodingAccessGuard aiCodingAccessGuard) {
        this(runtimeProxyClient, aiCodingAccessGuard, null, null, null);
    }

    public ControlRuntimePublicController(RuntimeProxyClient runtimeProxyClient,
                                          ControlAiCodingAccessGuard aiCodingAccessGuard,
                                          RuntimeAgentStreamProxy runtimeAgentStreamProxy) {
        this(runtimeProxyClient, aiCodingAccessGuard, runtimeAgentStreamProxy, null, null);
    }

    @Autowired
    public ControlRuntimePublicController(RuntimeProxyClient runtimeProxyClient,
                                          ControlAiCodingAccessGuard aiCodingAccessGuard,
                                          RuntimeAgentStreamProxy runtimeAgentStreamProxy,
                                          RuntimeTrustedAgentExecutionGateway trustedExecutionGateway,
                                          PlatformBearerAuthService bearerAuthService) {
        this.runtimeProxyClient = runtimeProxyClient;
        this.aiCodingAccessGuard = aiCodingAccessGuard;
        this.runtimeAgentStreamProxy = runtimeAgentStreamProxy;
        this.trustedExecutionGateway = trustedExecutionGateway;
        this.bearerAuthService = bearerAuthService;
    }

    @PostMapping("/api/runtime/agents/execute")
    public ResponseEntity<Map<String, Object>> executeAgent(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestBody Map<String, Object> body) {
        return executeAgentWithServerIdentity(authorization, body, false);
    }

    @PostMapping("/api/runtime/agents/execute/detailed")
    public ResponseEntity<Map<String, Object>> executeAgentDetailed(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestBody Map<String, Object> body) {
        return executeAgentWithServerIdentity(authorization, body, true);
    }

    @PostMapping(value = "/api/runtime/agents/execute/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<StreamingResponseBody> executeAgentStream(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestBody Map<String, Object> body) {
        return executeAgentStreamWithServerIdentity(authorization, body);
    }

    @DeleteMapping("/api/runtime/agents/sessions/{sessionId}")
    public ResponseEntity<Void> clearAgentSession(@PathVariable String sessionId) {
        return runtimeProxyClient.clearAgentSession(sessionId);
    }

    @GetMapping("/api/runtime/agents/route-evaluation")
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
    public ResponseEntity<List<Map<String, Object>>> runOpsRecent(
            @RequestParam(required = false) String projectCode,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String runType,
            @RequestParam(required = false) String entryType,
            @RequestParam(required = false) String agentId,
            @RequestParam(required = false) String userId,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(defaultValue = "7") int days) {
        return runtimeProxyClient.runOpsRecent(
                projectCode, status, runType, entryType, agentId, userId, keyword, limit, days);
    }

    @GetMapping("/api/runops/diagnostics")
    public ResponseEntity<Map<String, Object>> runOpsDiagnostics(
            @RequestParam(required = false) String projectCode,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String runType,
            @RequestParam(required = false) String entryType,
            @RequestParam(required = false) String agentId,
            @RequestParam(required = false) String userId,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(defaultValue = "7") int days) {
        return runtimeProxyClient.runOpsDiagnostics(
                projectCode, status, runType, entryType, agentId, userId, keyword, limit, days);
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

    @PostMapping({"/api/v1/agents/{key}/chat", "/gateway/agents/{key}/chat"})
    public ResponseEntity<Object> gatewayAgentChat(@PathVariable String key,
                                                   @RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> runtimeBody = new LinkedHashMap<>();
        if (body != null) {
            runtimeBody.putAll(body);
        }
        runtimeBody.put("agentId", key);
        runtimeBody.putIfAbsent("intentHint", "AGENT_GATEWAY_CHAT");
        runtimeBody.putIfAbsent("entryType", "GATEWAY");
        ResponseEntity<Map<String, Object>> response = runtimeProxyClient.executeAgent(runtimeBody);
        return ResponseEntity.status(response.getStatusCode())
                .headers(response.getHeaders())
                .body(response.getBody());
    }

    @GetMapping("/gateway/catalog")
    public ResponseEntity<Object> gatewayCatalog(@RequestParam(required = false) Long projectId) {
        ResponseEntity<Object> response = runtimeProxyClient.listAgents(projectId, null);
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

    @GetMapping("/api/workflows")
    public ResponseEntity<Object> listWorkflows(
            @RequestParam(required = false) Long projectId,
            @RequestParam(required = false) String projectCode,
            @RequestParam(required = false) String workflowKind,
            @RequestParam(required = false) String definitionAuthority,
            @RequestParam(required = false) String status) {
        return runtimeProxyClient.listWorkflows(
                projectId, projectCode, workflowKind, definitionAuthority, status);
    }

    @GetMapping("/api/workflows/search")
    public ResponseEntity<Object> searchWorkflows(
            @RequestParam(required = false) Long projectId,
            @RequestParam(required = false) String projectCode,
            @RequestParam(required = false) String workflowKind,
            @RequestParam(required = false) String definitionAuthority,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Integer current,
            @RequestParam(required = false) Integer size) {
        return runtimeProxyClient.searchWorkflows(
                projectId, projectCode, workflowKind, definitionAuthority,
                status, keyword, current, size);
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

    @GetMapping("/api/workflows/{id}/working-copy")
    public ResponseEntity<Object> workflowWorkingCopy(@PathVariable String id) {
        return runtimeProxyClient.workflowWorkingCopy(id);
    }

    @PutMapping("/api/workflows/{id}/working-copy")
    public ResponseEntity<Object> saveWorkflowWorkingCopy(@PathVariable String id,
                                                          @RequestBody Map<String, Object> body) {
        return runtimeProxyClient.saveWorkflowWorkingCopy(id, body);
    }

    @PostMapping("/api/workflows/studio/debug-node")
    public ResponseEntity<Object> debugWorkflowNode(@RequestBody Map<String, Object> body) {
        return runtimeProxyClient.debugWorkflowNode(body);
    }

    @PostMapping("/api/workflows/studio/debug-run")
    public ResponseEntity<Object> debugWorkflowRun(@RequestBody Map<String, Object> body) {
        return runtimeProxyClient.debugWorkflowRun(body);
    }

    @PostMapping("/api/workflows/studio/proposals/generate")
    public ResponseEntity<Object> generateWorkflowProposal(@RequestBody Map<String, Object> body) {
        return runtimeProxyClient.generateWorkflowProposal(body);
    }

    @PostMapping("/api/workflows/studio/proposals/edit")
    public ResponseEntity<Object> editWorkflowProposal(@RequestBody Map<String, Object> body) {
        return runtimeProxyClient.editWorkflowProposal(body);
    }

    @PostMapping("/api/workflows/ai-coding/workflows")
    public ResponseEntity<Object> createWorkflowAiCodingWorkflow(
            @RequestBody Map<String, Object> body,
            @RequestHeader(value = ControlAiCodingAccessGuard.AI_CODING_HEADER, required = false) String aiCodingKey) {
        if (aiCodingAccessGuard != null) {
            aiCodingAccessGuard.requireWorkflowCreateAccess(body, aiCodingKey);
        }
        return runtimeProxyClient.createWorkflowAiCodingWorkflow(body);
    }

    @GetMapping("/api/workflows/{workflowId}/ai-coding/context")
    public ResponseEntity<Object> workflowAiCodingContext(@PathVariable String workflowId) {
        return runtimeProxyClient.workflowAiCodingContext(workflowId);
    }

    @PutMapping("/api/workflows/{workflowId}/ai-coding/resource-bindings")
    public ResponseEntity<Object> replaceWorkflowAiCodingResourceBindings(
            @PathVariable String workflowId,
            @RequestBody Map<String, Object> body) {
        return runtimeProxyClient.replaceWorkflowAiCodingResourceBindings(workflowId, body);
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

    @PostMapping("/api/workflows/{workflowId}/versions/publish")
    public ResponseEntity<Object> publishWorkflowVersion(@PathVariable String workflowId,
                                                        @RequestBody Map<String, Object> body) {
        return runtimeProxyClient.publishWorkflowVersion(workflowId, body);
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

    @PostMapping("/api/workflows/{id}/page-assistant/attach-tool")
    public ResponseEntity<Object> attachPageAssistantWorkflowTool(@PathVariable String id,
                                                                  @RequestBody Map<String, Object> body) {
        return runtimeProxyClient.attachPageAssistantWorkflowTool(id, body);
    }

    @GetMapping("/api/workflows/credentials")
    public ResponseEntity<Object> listWorkflowCredentials(
            @RequestParam(required = false) Long projectId,
            @RequestParam(required = false) String projectCode) {
        return runtimeProxyClient.listWorkflowCredentials(projectId, projectCode);
    }

    @PostMapping("/api/workflows/credentials")
    public ResponseEntity<Object> createWorkflowCredential(@RequestBody Map<String, Object> body) {
        return runtimeProxyClient.createWorkflowCredential(body);
    }

    @PutMapping("/api/workflows/credentials/{id}")
    public ResponseEntity<Object> updateWorkflowCredential(@PathVariable Long id,
                                                          @RequestBody Map<String, Object> body) {
        return runtimeProxyClient.updateWorkflowCredential(id, body);
    }

    @DeleteMapping("/api/workflows/credentials/{id}")
    public ResponseEntity<Object> deleteWorkflowCredential(@PathVariable Long id) {
        return runtimeProxyClient.deleteWorkflowCredential(id);
    }

    @GetMapping("/api/runtime/evals/datasets")
    public ResponseEntity<Object> listEvalDatasets(@RequestParam(required = false) String agentId) {
        return runtimeProxyClient.listEvalDatasets(agentId);
    }

    @PostMapping("/api/runtime/evals/datasets")
    public ResponseEntity<Object> createEvalDataset(@RequestBody Map<String, Object> body) {
        return runtimeProxyClient.createEvalDataset(body);
    }

    @PostMapping("/api/runtime/evals/datasets/{datasetId}/cases/import")
    public ResponseEntity<Object> importEvalCases(@PathVariable Long datasetId,
                                                  @RequestBody Map<String, Object> body) {
        return runtimeProxyClient.importEvalCases(datasetId, body);
    }

    @GetMapping("/api/runtime/evals/datasets/{datasetId}/cases")
    public ResponseEntity<Object> listEvalCases(@PathVariable Long datasetId) {
        return runtimeProxyClient.listEvalCases(datasetId);
    }

    @PostMapping("/api/runtime/evals/runs")
    public ResponseEntity<Object> startEvalRun(@RequestBody Map<String, Object> body) {
        return runtimeProxyClient.startEvalRun(body);
    }

    @GetMapping("/api/runtime/evals/runs/{runId}")
    public ResponseEntity<Object> getEvalRun(@PathVariable Long runId) {
        return runtimeProxyClient.getEvalRun(runId);
    }

    @GetMapping("/api/runtime/evals/runs/{runId}/results")
    public ResponseEntity<Object> listEvalRunResults(@PathVariable Long runId) {
        return runtimeProxyClient.listEvalRunResults(runId);
    }

    @GetMapping("/api/agents")
    public ResponseEntity<Object> listAgents(
            @RequestParam(required = false) Long projectId,
            @RequestParam(required = false) String projectCode) {
        return runtimeProxyClient.listAgents(projectId, projectCode);
    }

    @GetMapping("/api/agents/statistics")
    public ResponseEntity<Object> getAgentStatistics(
            @RequestParam(required = false) Long projectId,
            @RequestParam(required = false) String projectCode) {
        return runtimeProxyClient.getAgentStatistics(projectId, projectCode);
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

    @GetMapping("/api/agents/{agentId}/config-versions")
    public ResponseEntity<Object> listAgentConfigVersions(@PathVariable String agentId) {
        return runtimeProxyClient.listAgentConfigVersions(agentId);
    }

    @PutMapping("/api/agents/{agentId}/config-versions/draft")
    public ResponseEntity<Object> saveAgentConfigDraft(@PathVariable String agentId,
                                                       @RequestBody Map<String, Object> body) {
        return runtimeProxyClient.saveAgentConfigDraft(agentId, body);
    }

    @PostMapping("/api/agents/{agentId}/config-versions/{configVersionId}/publish")
    public ResponseEntity<Object> publishAgentConfigVersion(@PathVariable String agentId,
                                                            @PathVariable Long configVersionId,
                                                            @RequestBody(required = false) Map<String, Object> body) {
        return runtimeProxyClient.publishAgentConfigVersion(
                agentId,
                configVersionId,
                body == null ? Map.of() : body);
    }

    @PostMapping("/api/agents/{agentId}/config-versions/{configVersionId}/copy-to-draft")
    public ResponseEntity<Object> copyAgentConfigVersionToDraft(@PathVariable String agentId,
                                                                @PathVariable Long configVersionId) {
        return runtimeProxyClient.copyAgentConfigVersionToDraft(agentId, configVersionId);
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
        // Workflow wfi_ sessions must use authenticated Embed/Agent paths, not this compatibility proxy.
        if (sessionId != null && sessionId.trim().startsWith("wfi_")) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
                    "success", false,
                    "code", "RUNTIME_INTERACTION_FORBIDDEN",
                    "answer", "Workflow interactions cannot be resumed via the public compatibility endpoint",
                    "interactionId", sessionId.trim()));
        }
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

    @PostMapping(value = "/api/runtime/debug-sessions/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<StreamingResponseBody> createRuntimeDebugSessionStream(
            @RequestBody Map<String, Object> body) {
        return streamDebugProxy(RuntimeAgentStreamProxy.DEBUG_SESSION_STREAM, body);
    }

    @PostMapping(value = "/api/runtime/debug-sessions/{sessionId}/submit/stream",
            produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<StreamingResponseBody> submitRuntimeDebugSessionStream(
            @PathVariable String sessionId,
            @RequestBody Map<String, Object> body) {
        if (runtimeAgentStreamProxy == null) {
            throw new IllegalStateException("Runtime stream proxy is not configured");
        }
        StreamingResponseBody stream = outputStream ->
                runtimeAgentStreamProxy.streamDebugSubmit(sessionId, body, outputStream);
        return streamResponse(stream);
    }

    @GetMapping("/api/runtime/interactions/human-approvals")
    public ResponseEntity<Object> listHumanApprovals(@RequestParam(required = false) String agentId,
                                                     @RequestParam(required = false) String userId,
                                                     @RequestParam(defaultValue = "50") int limit) {
        return runtimeProxyClient.listHumanApprovals(agentId, userId, limit);
    }

    @PostMapping("/api/runtime/interactions/human-approvals/{interactionId}/submit")
    public ResponseEntity<Object> submitHumanApproval(@PathVariable String interactionId,
                                                      @RequestBody Map<String, Object> body) {
        return runtimeProxyClient.submitHumanApproval(interactionId, body);
    }

    @DeleteMapping("/api/runtime/interactions/human-approvals/{interactionId}")
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

    /**
     * Prefer server-attested platform login identity via internal Runtime contract.
     * Without a real Bearer session, forward to public Runtime execute with userTrusted=false
     * (body userId is business data only and never becomes ACL identity).
     */
    private ResponseEntity<Map<String, Object>> executeAgentWithServerIdentity(
            String authorization,
            Map<String, Object> body,
            boolean detailed) {
        Map<String, Object> safeBody = body == null ? new LinkedHashMap<>() : new LinkedHashMap<>(body);
        // Never accept client-injected trust envelopes on the public surface.
        safeBody.remove("__workflowExecutionIdentity");
        safeBody.remove("trustedIdentity");
        safeBody.remove("_trustedUserId");

        Optional<PlatformUserEntity> user = bearerAuthService == null
                ? Optional.empty()
                : bearerAuthService.resolveBearerUser(authorization);
        if (user.isPresent() && trustedExecutionGateway != null) {
            String trustedUserId = String.valueOf(user.get().getId());
            ResponseEntity<Map<String, Object>> trusted =
                    trustedExecutionGateway.executeTrusted(safeBody, "AGENT", trustedUserId);
            if (!detailed) {
                return trusted;
            }
            // Detailed public route historically hit /execute/detailed; when identity is trusted,
            // reuse the same result envelope (steps may be absent — acceptable fail-closed tradeoff).
            return trusted;
        }
        if (detailed) {
            return runtimeProxyClient.executeAgentDetailed(safeBody);
        }
        return runtimeProxyClient.executeAgent(safeBody);
    }

    private ResponseEntity<StreamingResponseBody> executeAgentStreamWithServerIdentity(
            String authorization,
            Map<String, Object> body) {
        if (runtimeAgentStreamProxy == null) {
            throw new IllegalStateException("Runtime stream proxy is not configured");
        }
        Map<String, Object> safeBody = body == null ? new LinkedHashMap<>() : new LinkedHashMap<>(body);
        safeBody.remove("__workflowExecutionIdentity");
        safeBody.remove("trustedIdentity");
        safeBody.remove("_trustedUserId");
        Optional<PlatformUserEntity> user = bearerAuthService == null
                ? Optional.empty()
                : bearerAuthService.resolveBearerUser(authorization);
        StreamingResponseBody stream;
        if (user.isPresent() && trustedExecutionGateway != null) {
            String trustedUserId = String.valueOf(user.get().getId());
            stream = outputStream -> trustedExecutionGateway.streamTrusted(
                    safeBody, "AGENT", trustedUserId, outputStream, SseStreamRelay.passthrough());
        } else {
            stream = outputStream -> runtimeAgentStreamProxy.stream(safeBody, outputStream);
        }
        return streamResponse(stream);
    }

    private ResponseEntity<StreamingResponseBody> streamDebugProxy(String path, Map<String, Object> body) {
        if (runtimeAgentStreamProxy == null) {
            throw new IllegalStateException("Runtime stream proxy is not configured");
        }
        StreamingResponseBody stream = outputStream -> runtimeAgentStreamProxy.stream(path, body, outputStream);
        return streamResponse(stream);
    }

    private ResponseEntity<StreamingResponseBody> streamResponse(StreamingResponseBody stream) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noCache().noTransform())
                .header("X-Accel-Buffering", "no")
                .contentType(MediaType.TEXT_EVENT_STREAM)
                .body(stream);
    }
}
