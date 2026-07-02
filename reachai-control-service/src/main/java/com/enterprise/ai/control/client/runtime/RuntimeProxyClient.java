package com.enterprise.ai.control.client.runtime;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.Map;

@FeignClient(name = "reachai-runtime-proxy", url = "${services.runtime-service.url:http://localhost:18604}")
public interface RuntimeProxyClient {

    @RequestMapping(method = RequestMethod.POST, path = "/api/agent/execute")
    ResponseEntity<Map<String, Object>> executeAgent(@RequestBody Map<String, Object> body);

    @RequestMapping(method = RequestMethod.POST, path = "/api/agent/execute/detailed")
    ResponseEntity<Map<String, Object>> executeAgentDetailed(@RequestBody Map<String, Object> body);

    @RequestMapping(method = RequestMethod.GET, path = "/api/agent/route-evaluation")
    ResponseEntity<Map<String, Object>> routeEvaluation(@RequestParam("days") int days);

    @RequestMapping(method = RequestMethod.GET, path = "/api/traces/{traceId}")
    ResponseEntity<Map<String, Object>> getTrace(@PathVariable("traceId") String traceId);

    @RequestMapping(method = RequestMethod.GET, path = "/api/traces/recent")
    ResponseEntity<Map<String, Object>> listRecentTraces(@RequestParam(value = "userId", required = false) String userId,
                                                         @RequestParam("days") int days,
                                                         @RequestParam("limit") int limit);

    @RequestMapping(method = RequestMethod.GET, path = "/api/runops/traces/{traceId}")
    ResponseEntity<Map<String, Object>> runOpsDetail(@PathVariable("traceId") String traceId);

    @RequestMapping(method = RequestMethod.GET, path = "/api/runops/traces/recent")
    ResponseEntity<Map<String, Object>> runOpsRecent(@RequestParam(value = "userId", required = false) String userId,
                                                     @RequestParam("limit") int limit,
                                                     @RequestParam("days") int days);

    @RequestMapping(method = RequestMethod.GET, path = "/api/runops/diagnostics")
    ResponseEntity<Map<String, Object>> runOpsDiagnostics(@RequestParam(value = "userId", required = false) String userId,
                                                          @RequestParam("limit") int limit,
                                                          @RequestParam("days") int days);

    @RequestMapping(method = RequestMethod.GET, path = "/api/trace-center/guard-decisions")
    ResponseEntity<Object> listGuardDecisions(@RequestParam(value = "traceId", required = false) String traceId,
                                              @RequestParam(value = "decisionType", required = false) String decisionType,
                                              @RequestParam(value = "targetKind", required = false) String targetKind,
                                              @RequestParam(value = "targetName", required = false) String targetName,
                                              @RequestParam(value = "decision", required = false) String decision,
                                              @RequestParam(value = "from", required = false) String from,
                                              @RequestParam(value = "to", required = false) String to,
                                              @RequestParam("limit") int limit);

    @RequestMapping(method = RequestMethod.GET, path = "/api/runops/traces/{traceId}/compare/{candidateTraceId}")
    ResponseEntity<Map<String, Object>> runOpsCompare(@PathVariable("traceId") String traceId,
                                                      @PathVariable("candidateTraceId") String candidateTraceId);

    @RequestMapping(method = RequestMethod.POST, path = "/api/runops/traces/{traceId}/replay")
    ResponseEntity<Map<String, Object>> runOpsReplay(@PathVariable("traceId") String traceId,
                                                     @RequestBody Map<String, Object> body);

    @RequestMapping(method = RequestMethod.POST, path = "/api/chat")
    ResponseEntity<Object> chat(@RequestBody Map<String, Object> body);

    @RequestMapping(method = RequestMethod.POST, path = "/api/chat/stream",
            produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    ResponseEntity<String> chatStream(@RequestBody Map<String, Object> body);

    @RequestMapping(method = RequestMethod.DELETE, path = "/api/chat/session/{sessionId}")
    ResponseEntity<Object> clearChatSession(@PathVariable("sessionId") String sessionId);

    @RequestMapping(method = RequestMethod.GET, path = "/api/workflows")
    ResponseEntity<Object> listWorkflows(@RequestParam(value = "projectId", required = false) Long projectId,
                                         @RequestParam(value = "projectCode", required = false) String projectCode,
                                         @RequestParam(value = "workflowType", required = false) String workflowType,
                                         @RequestParam(value = "status", required = false) String status);

    @RequestMapping(method = RequestMethod.POST, path = "/api/workflows")
    ResponseEntity<Object> createWorkflow(@RequestBody Map<String, Object> body);

    @RequestMapping(method = RequestMethod.GET, path = "/api/workflows/{id}")
    ResponseEntity<Object> getWorkflow(@PathVariable("id") String id);

    @RequestMapping(method = RequestMethod.PUT, path = "/api/workflows/{id}")
    ResponseEntity<Object> updateWorkflow(@PathVariable("id") String id,
                                          @RequestBody Map<String, Object> body);

    @RequestMapping(method = RequestMethod.DELETE, path = "/api/workflows/{id}")
    ResponseEntity<Object> deleteWorkflow(@PathVariable("id") String id);

    @RequestMapping(method = RequestMethod.GET, path = "/api/workflows/graph-node-types")
    ResponseEntity<Object> graphNodeTypes();

    @RequestMapping(method = RequestMethod.POST, path = "/api/workflows/runtime-validation")
    ResponseEntity<Object> validateWorkflowRuntime(@RequestBody Map<String, Object> body);

    @RequestMapping(method = RequestMethod.GET, path = "/api/workflows/{id}/studio")
    ResponseEntity<Object> workflowStudio(@PathVariable("id") String id);

    @RequestMapping(method = RequestMethod.PUT, path = "/api/workflows/{id}/studio")
    ResponseEntity<Object> saveWorkflowStudio(@PathVariable("id") String id,
                                              @RequestBody Map<String, Object> body);

    @RequestMapping(method = RequestMethod.POST, path = "/api/workflows/studio/debug-node")
    ResponseEntity<Object> debugWorkflowNode(@RequestBody Map<String, Object> body);

    @RequestMapping(method = RequestMethod.POST, path = "/api/workflows/studio/debug-run")
    ResponseEntity<Object> debugWorkflowRun(@RequestBody Map<String, Object> body);

    @RequestMapping(method = RequestMethod.POST, path = "/api/workflows/studio/generate-draft")
    ResponseEntity<Object> generateWorkflowStudioDraft(@RequestBody Map<String, Object> body);

    @RequestMapping(method = RequestMethod.POST, path = "/api/workflows/studio/edit-draft")
    ResponseEntity<Object> editWorkflowStudioDraft(@RequestBody Map<String, Object> body);

    @RequestMapping(method = RequestMethod.POST, path = "/api/workflows/ai-coding/workflows")
    ResponseEntity<Object> createWorkflowAiCodingWorkflow(@RequestBody Map<String, Object> body);

    @RequestMapping(method = RequestMethod.GET, path = "/api/workflows/{workflowId}/ai-coding/context")
    ResponseEntity<Object> workflowAiCodingContext(@PathVariable("workflowId") String workflowId);

    @RequestMapping(method = RequestMethod.POST, path = "/api/workflows/{workflowId}/ai-coding/validate")
    ResponseEntity<Object> validateWorkflowAiCoding(@PathVariable("workflowId") String workflowId,
                                                    @RequestBody Map<String, Object> body);

    @RequestMapping(method = RequestMethod.POST, path = "/api/workflows/{workflowId}/ai-coding/patch")
    ResponseEntity<Object> patchWorkflowAiCoding(@PathVariable("workflowId") String workflowId,
                                                 @RequestBody Map<String, Object> body);

    @RequestMapping(method = RequestMethod.POST, path = "/api/workflows/{workflowId}/ai-coding/run")
    ResponseEntity<Object> runWorkflowAiCoding(@PathVariable("workflowId") String workflowId,
                                               @RequestBody Map<String, Object> body);

    @RequestMapping(method = RequestMethod.GET, path = "/api/workflows/{workflowId}/ai-coding/versions")
    ResponseEntity<Object> workflowAiCodingVersions(@PathVariable("workflowId") String workflowId);

    @RequestMapping(method = RequestMethod.POST, path = "/api/workflows/{workflowId}/ai-coding/publish")
    ResponseEntity<Object> publishWorkflowAiCoding(@PathVariable("workflowId") String workflowId,
                                                   @RequestBody Map<String, Object> body);

    @RequestMapping(method = RequestMethod.GET, path = "/api/workflows/{workflowId}/ai-coding/runs")
    ResponseEntity<Object> workflowAiCodingRuns(@PathVariable("workflowId") String workflowId,
                                                @RequestParam(value = "limit", required = false) Integer limit,
                                                @RequestParam(value = "days", required = false) Integer days);

    @RequestMapping(method = RequestMethod.GET, path = "/api/workflows/{workflowId}/ai-coding/runs/{traceId}")
    ResponseEntity<Object> workflowAiCodingRunDetail(@PathVariable("workflowId") String workflowId,
                                                     @PathVariable("traceId") String traceId);

    @RequestMapping(method = RequestMethod.GET, path = "/api/workflows/{workflowId}/ai-coding/page-assistant/catalog")
    ResponseEntity<Object> workflowAiCodingPageAssistantCatalog(@PathVariable("workflowId") String workflowId);

    @RequestMapping(method = RequestMethod.POST, path = "/api/workflows/{workflowId}/ai-coding/page-assistant/validate")
    ResponseEntity<Object> validateWorkflowAiCodingPageAssistant(@PathVariable("workflowId") String workflowId,
                                                                 @RequestBody Map<String, Object> body);

    @RequestMapping(method = RequestMethod.POST, path = "/api/workflows/{workflowId}/ai-coding/page-assistant/smoke-test")
    ResponseEntity<Object> smokeTestWorkflowAiCodingPageAssistant(@PathVariable("workflowId") String workflowId,
                                                                  @RequestBody Map<String, Object> body);

    @RequestMapping(method = RequestMethod.GET, path = "/api/workflows/{workflowId}/versions")
    ResponseEntity<Object> listWorkflowVersions(@PathVariable("workflowId") String workflowId);

    @RequestMapping(method = RequestMethod.POST, path = "/api/workflows/{workflowId}/versions")
    ResponseEntity<Object> publishWorkflowVersion(@PathVariable("workflowId") String workflowId,
                                                  @RequestBody Map<String, Object> body);

    @RequestMapping(method = RequestMethod.POST, path = "/api/workflows/{workflowId}/versions/publish")
    ResponseEntity<Object> publishWorkflowVersionExplicit(@PathVariable("workflowId") String workflowId,
                                                          @RequestBody Map<String, Object> body);

    @RequestMapping(method = RequestMethod.POST, path = "/api/workflows/{workflowId}/versions/validate")
    ResponseEntity<Object> validateWorkflowVersion(@PathVariable("workflowId") String workflowId);

    @RequestMapping(method = RequestMethod.POST, path = "/api/workflows/{workflowId}/versions/{versionId}/rollback")
    ResponseEntity<Object> rollbackWorkflowVersion(@PathVariable("workflowId") String workflowId,
                                                   @PathVariable("versionId") Long versionId,
                                                   @RequestBody Map<String, Object> body);

    @RequestMapping(method = RequestMethod.POST, path = "/api/workflows/{id}/page-assistant/bind")
    ResponseEntity<Object> bindPageAssistantWorkflow(@PathVariable("id") String id,
                                                     @RequestBody Map<String, Object> body);

    @RequestMapping(method = RequestMethod.GET, path = "/api/agent/workflow-credentials")
    ResponseEntity<Object> listWorkflowCredentials(@RequestParam(value = "projectId", required = false) Long projectId,
                                                   @RequestParam(value = "projectCode", required = false) String projectCode);

    @RequestMapping(method = RequestMethod.POST, path = "/api/agent/workflow-credentials")
    ResponseEntity<Object> createWorkflowCredential(@RequestBody Map<String, Object> body);

    @RequestMapping(method = RequestMethod.PUT, path = "/api/agent/workflow-credentials/{id}")
    ResponseEntity<Object> updateWorkflowCredential(@PathVariable("id") Long id,
                                                    @RequestBody Map<String, Object> body);

    @RequestMapping(method = RequestMethod.DELETE, path = "/api/agent/workflow-credentials/{id}")
    ResponseEntity<Object> deleteWorkflowCredential(@PathVariable("id") Long id);

    @RequestMapping(method = RequestMethod.GET, path = "/api/agent/evals/datasets")
    ResponseEntity<Object> listEvalDatasets(@RequestParam(value = "agentId", required = false) String agentId);

    @RequestMapping(method = RequestMethod.POST, path = "/api/agent/evals/datasets")
    ResponseEntity<Object> createEvalDataset(@RequestBody Map<String, Object> body);

    @RequestMapping(method = RequestMethod.POST, path = "/api/agent/evals/datasets/{datasetId}/cases/import")
    ResponseEntity<Object> importEvalCases(@PathVariable("datasetId") Long datasetId,
                                           @RequestBody Map<String, Object> body);

    @RequestMapping(method = RequestMethod.GET, path = "/api/agent/evals/datasets/{datasetId}/cases")
    ResponseEntity<Object> listEvalCases(@PathVariable("datasetId") Long datasetId);

    @RequestMapping(method = RequestMethod.POST, path = "/api/agent/evals/runs")
    ResponseEntity<Object> startEvalRun(@RequestBody Map<String, Object> body);

    @RequestMapping(method = RequestMethod.GET, path = "/api/agent/evals/runs/{runId}")
    ResponseEntity<Object> getEvalRun(@PathVariable("runId") Long runId);

    @RequestMapping(method = RequestMethod.GET, path = "/api/agent/evals/runs/{runId}/results")
    ResponseEntity<Object> listEvalRunResults(@PathVariable("runId") Long runId);

    @RequestMapping(method = RequestMethod.GET, path = "/api/agents")
    ResponseEntity<Object> listAgents(@RequestParam(value = "projectId", required = false) Long projectId,
                                      @RequestParam(value = "projectCode", required = false) String projectCode,
                                      @RequestParam(value = "agentKind", required = false) String agentKind);

    @RequestMapping(method = RequestMethod.POST, path = "/api/agents")
    ResponseEntity<Object> createAgent(@RequestBody Map<String, Object> body);

    @RequestMapping(method = RequestMethod.GET, path = "/api/agents/{id}")
    ResponseEntity<Object> getAgent(@PathVariable("id") String id);

    @RequestMapping(method = RequestMethod.PUT, path = "/api/agents/{id}")
    ResponseEntity<Object> updateAgent(@PathVariable("id") String id,
                                       @RequestBody Map<String, Object> body);

    @RequestMapping(method = RequestMethod.DELETE, path = "/api/agents/{id}")
    ResponseEntity<Object> deleteAgent(@PathVariable("id") String id);

    @RequestMapping(method = RequestMethod.GET, path = "/api/agents/{agentId}/workflow-bindings")
    ResponseEntity<Object> listAgentWorkflowBindings(@PathVariable("agentId") String agentId);

    @RequestMapping(method = RequestMethod.POST, path = "/api/agents/{agentId}/workflow-bindings")
    ResponseEntity<Object> createAgentWorkflowBinding(@PathVariable("agentId") String agentId,
                                                      @RequestBody Map<String, Object> body);

    @RequestMapping(method = RequestMethod.PUT, path = "/api/agents/{agentId}/workflow-bindings/{bindingId}")
    ResponseEntity<Object> updateAgentWorkflowBinding(@PathVariable("agentId") String agentId,
                                                      @PathVariable("bindingId") Long bindingId,
                                                      @RequestBody Map<String, Object> body);

    @RequestMapping(method = RequestMethod.DELETE, path = "/api/agents/{agentId}/workflow-bindings/{bindingId}")
    ResponseEntity<Object> deleteAgentWorkflowBinding(@PathVariable("agentId") String agentId,
                                                      @PathVariable("bindingId") Long bindingId);

    @RequestMapping(method = RequestMethod.POST, path = "/api/agents/{agentId}/workflow-bindings/resolve-preview")
    ResponseEntity<Object> resolveAgentWorkflowBindingPreview(@PathVariable("agentId") String agentId,
                                                              @RequestBody Map<String, Object> body);

    @RequestMapping(method = RequestMethod.GET, path = "/api/runtimes")
    ResponseEntity<Object> listRuntimes();

    @RequestMapping(method = RequestMethod.POST, path = "/api/runtimes/embedded/dispatch")
    ResponseEntity<Object> dispatchEmbeddedRuntime(@RequestBody Map<String, Object> body);

    @RequestMapping(method = RequestMethod.POST, path = "/api/runtime/tools/{qualifiedName}/execute")
    ResponseEntity<Object> executeRuntimeTool(@PathVariable("qualifiedName") String qualifiedName,
                                              @RequestBody Map<String, Object> body);

    @RequestMapping(method = RequestMethod.POST, path = "/api/runtime/compositions/{qualifiedName}/execute")
    ResponseEntity<Object> executeRuntimeComposition(@PathVariable("qualifiedName") String qualifiedName,
                                                     @RequestBody Map<String, Object> body);

    @RequestMapping(method = RequestMethod.POST, path = "/api/runtime/interactions/{sessionId}/resume")
    ResponseEntity<Object> resumeRuntimeInteraction(@PathVariable("sessionId") String sessionId,
                                                    @RequestBody Map<String, Object> body);

    @RequestMapping(method = RequestMethod.POST, path = "/api/runtime/debug-sessions")
    ResponseEntity<Object> createRuntimeDebugSession(@RequestBody Map<String, Object> body);

    @RequestMapping(method = RequestMethod.GET, path = "/api/runtime/debug-sessions/{sessionId}")
    ResponseEntity<Object> getRuntimeDebugSession(@PathVariable("sessionId") String sessionId);

    @RequestMapping(method = RequestMethod.POST, path = "/api/runtime/debug-sessions/{sessionId}/submit")
    ResponseEntity<Object> submitRuntimeDebugSession(@PathVariable("sessionId") String sessionId,
                                                     @RequestBody Map<String, Object> body);

    @RequestMapping(method = RequestMethod.POST, path = "/api/runtime/debug-sessions/{sessionId}/cancel")
    ResponseEntity<Object> cancelRuntimeDebugSession(@PathVariable("sessionId") String sessionId);

    @RequestMapping(method = RequestMethod.GET, path = "/api/agent/interactions/human-approvals")
    ResponseEntity<Object> listHumanApprovals(@RequestParam(value = "agentId", required = false) Long agentId,
                                              @RequestParam(value = "userId", required = false) String userId,
                                              @RequestParam("limit") int limit);

    @RequestMapping(method = RequestMethod.POST, path = "/api/agent/interactions/human-approvals/{interactionId}/submit")
    ResponseEntity<Object> submitHumanApproval(@PathVariable("interactionId") String interactionId,
                                               @RequestBody Map<String, Object> body);

    @RequestMapping(method = RequestMethod.DELETE, path = "/api/agent/interactions/human-approvals/{interactionId}")
    ResponseEntity<Object> cancelHumanApproval(@PathVariable("interactionId") String interactionId,
                                               @RequestParam(value = "userId", required = false) String userId);

    @RequestMapping(method = RequestMethod.POST, path = "/api/registry/projects/{projectCode}/agent-graphs/sync")
    ResponseEntity<Object> syncAgentGraphs(@PathVariable("projectCode") String projectCode,
                                           @RequestBody Map<String, Object> body);
}
