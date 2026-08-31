package com.enterprise.ai.control.client.runtime;

import com.enterprise.ai.control.pageworkbench.application.PageWorkbenchContract.PublishedWorkflowView;
import com.enterprise.ai.control.pageworkbench.application.PageWorkbenchContract.WorkflowDeliveryView;
import com.enterprise.ai.control.pageworkbench.application.PageWorkbenchContract.WorkflowEngineeringDraftView;
import com.enterprise.ai.control.pageworkbench.application.PageWorkbenchContract.WorkflowExecutionReadinessView;
import com.enterprise.ai.control.pageworkbench.application.PageWorkbenchContract.WorkflowReleaseReadinessView;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;
import java.util.Map;

@FeignClient(name = "reachai-runtime-proxy", url = "${services.runtime-service.url:http://localhost:18604}")
public interface RuntimeProxyClient {

    @RequestMapping(method = RequestMethod.POST, path = "/api/runtime/agents/execute")
    ResponseEntity<Map<String, Object>> executeAgent(@RequestBody Map<String, Object> body);

    @RequestMapping(method = RequestMethod.POST, path = "/api/runtime/agents/execute/detailed")
    ResponseEntity<Map<String, Object>> executeAgentDetailed(@RequestBody Map<String, Object> body);

    @RequestMapping(method = RequestMethod.GET, path = "/api/runtime/agents/route-evaluation")
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
    ResponseEntity<List<Map<String, Object>>> runOpsRecent(
                                                     @RequestParam(value = "projectCode", required = false) String projectCode,
                                                     @RequestParam(value = "status", required = false) String status,
                                                     @RequestParam(value = "runType", required = false) String runType,
                                                     @RequestParam(value = "entryType", required = false) String entryType,
                                                     @RequestParam(value = "agentId", required = false) String agentId,
                                                     @RequestParam(value = "userId", required = false) String userId,
                                                     @RequestParam(value = "keyword", required = false) String keyword,
                                                     @RequestParam("limit") int limit,
                                                     @RequestParam("days") int days);

    @RequestMapping(method = RequestMethod.GET, path = "/api/runops/diagnostics")
    ResponseEntity<Map<String, Object>> runOpsDiagnostics(
                                                          @RequestParam(value = "projectCode", required = false) String projectCode,
                                                          @RequestParam(value = "status", required = false) String status,
                                                          @RequestParam(value = "runType", required = false) String runType,
                                                          @RequestParam(value = "entryType", required = false) String entryType,
                                                          @RequestParam(value = "agentId", required = false) String agentId,
                                                          @RequestParam(value = "userId", required = false) String userId,
                                                          @RequestParam(value = "keyword", required = false) String keyword,
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

    @RequestMapping(method = RequestMethod.GET, path = "/api/workflows")
    ResponseEntity<Object> listWorkflows(@RequestParam(value = "projectId", required = false) Long projectId,
                                         @RequestParam(value = "projectCode", required = false) String projectCode,
                                         @RequestParam(value = "workflowKind", required = false) String workflowKind,
                                         @RequestParam(value = "definitionAuthority", required = false) String definitionAuthority,
                                         @RequestParam(value = "status", required = false) String status);

    @RequestMapping(method = RequestMethod.GET, path = "/api/workflows/search")
    ResponseEntity<Object> searchWorkflows(@RequestParam(value = "projectId", required = false) Long projectId,
                                           @RequestParam(value = "projectCode", required = false) String projectCode,
                                           @RequestParam(value = "workflowKind", required = false) String workflowKind,
                                           @RequestParam(value = "definitionAuthority", required = false) String definitionAuthority,
                                           @RequestParam(value = "status", required = false) String status,
                                           @RequestParam(value = "keyword", required = false) String keyword,
                                           @RequestParam(value = "current", required = false) Integer current,
                                           @RequestParam(value = "size", required = false) Integer size);

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

    @RequestMapping(method = RequestMethod.GET,
            path = "/internal/runtime/page-workbench/workflow-node-types")
    ResponseEntity<Object> pageWorkbenchWorkflowNodeTypes();

    @RequestMapping(method = RequestMethod.POST, path = "/api/workflows/runtime-validation")
    ResponseEntity<Object> validateWorkflowRuntime(@RequestBody Map<String, Object> body);

    @RequestMapping(method = RequestMethod.GET, path = "/api/workflows/{id}/working-copy")
    ResponseEntity<Object> workflowWorkingCopy(@PathVariable("id") String id);

    @RequestMapping(method = RequestMethod.PUT, path = "/api/workflows/{id}/working-copy")
    ResponseEntity<Object> saveWorkflowWorkingCopy(@PathVariable("id") String id,
                                                   @RequestBody Map<String, Object> body);

    @RequestMapping(method = RequestMethod.POST, path = "/api/workflows/studio/debug-node")
    ResponseEntity<Object> debugWorkflowNode(@RequestBody Map<String, Object> body);

    @RequestMapping(method = RequestMethod.POST, path = "/api/workflows/studio/debug-run")
    ResponseEntity<Object> debugWorkflowRun(@RequestBody Map<String, Object> body);

    @RequestMapping(method = RequestMethod.POST, path = "/api/workflows/studio/proposals/generate")
    ResponseEntity<Object> generateWorkflowProposal(@RequestBody Map<String, Object> body);

    @RequestMapping(method = RequestMethod.POST, path = "/api/workflows/studio/proposals/edit")
    ResponseEntity<Object> editWorkflowProposal(@RequestBody Map<String, Object> body);

    @RequestMapping(method = RequestMethod.POST, path = "/api/workflows/ai-coding/workflows")
    ResponseEntity<Object> createWorkflowAiCodingWorkflow(@RequestBody Map<String, Object> body);

    @RequestMapping(method = RequestMethod.POST,
            path = "/internal/runtime/runops/workflow-candidates/drafts")
    ResponseEntity<Object> createTraceWorkflowCandidateDraft(
            @RequestBody Map<String, Object> body);

    @RequestMapping(method = RequestMethod.GET, path = "/api/workflows/{workflowId}/ai-coding/context")
    ResponseEntity<Object> workflowAiCodingContext(@PathVariable("workflowId") String workflowId);

    @RequestMapping(method = RequestMethod.PUT, path = "/api/workflows/{workflowId}/ai-coding/resource-bindings")
    ResponseEntity<Object> replaceWorkflowAiCodingResourceBindings(
            @PathVariable("workflowId") String workflowId,
            @RequestBody Map<String, Object> body);

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

    @RequestMapping(method = RequestMethod.POST, path = "/api/workflows/{workflowId}/versions/publish")
    ResponseEntity<Object> publishWorkflowVersion(@PathVariable("workflowId") String workflowId,
                                                  @RequestBody Map<String, Object> body);

    @RequestMapping(method = RequestMethod.POST, path = "/api/workflows/{workflowId}/versions/validate")
    ResponseEntity<Object> validateWorkflowVersion(@PathVariable("workflowId") String workflowId);

    @RequestMapping(method = RequestMethod.POST, path = "/api/workflows/{workflowId}/versions/{versionId}/rollback")
    ResponseEntity<Object> rollbackWorkflowVersion(@PathVariable("workflowId") String workflowId,
                                                   @PathVariable("versionId") Long versionId,
                                                   @RequestBody Map<String, Object> body);

    @RequestMapping(method = RequestMethod.POST, path = "/api/workflows/{id}/page-assistant/attach-tool")
    ResponseEntity<Object> attachPageAssistantWorkflowTool(@PathVariable("id") String id,
                                                           @RequestBody Map<String, Object> body);

    @RequestMapping(method = RequestMethod.POST,
            path = "/internal/runtime/projects/{projectId}/agent-supervisor/workflow-tools/attach")
    ResponseEntity<Object> attachAgentSupervisorWorkflowTool(@PathVariable("projectId") Long projectId,
                                                             @RequestBody Map<String, Object> body);

    @RequestMapping(method = RequestMethod.POST,
            path = "/internal/runtime/page-workbench/projects/{projectCode}/workflow-drafts")
    ResponseEntity<WorkflowEngineeringDraftView>
            createPageWorkbenchWorkflowDraft(
                    @PathVariable("projectCode") String projectCode,
                    @RequestBody Map<String, Object> body);

    @RequestMapping(method = RequestMethod.POST,
            path = "/internal/runtime/page-workbench/projects/{projectCode}/workflows/{workflowId}/deliver")
    ResponseEntity<WorkflowDeliveryView> deliverPageWorkbenchWorkflow(
            @PathVariable("projectCode") String projectCode,
            @PathVariable("workflowId") String workflowId,
            @RequestBody Map<String, Object> body);

    @RequestMapping(method = RequestMethod.GET,
            path = "/internal/runtime/page-workbench/projects/{projectCode}/published")
    ResponseEntity<List<PublishedWorkflowView>> pageWorkbenchPublished(
            @PathVariable("projectCode") String projectCode,
            @RequestParam(value = "pageKey", required = false) String pageKey);

    @RequestMapping(method = RequestMethod.GET,
            path = "/internal/runtime/page-workbench/projects/{projectCode}/release-readiness")
    ResponseEntity<WorkflowReleaseReadinessView> pageWorkbenchReleaseReadiness(
            @PathVariable("projectCode") String projectCode,
            @RequestParam("pageKey") String pageKey,
            @RequestParam("workflowId") String workflowId,
            @RequestParam("workflowVersion") String workflowVersion);

    @RequestMapping(method = RequestMethod.GET,
            path = "/internal/runtime/page-workbench/projects/{projectCode}/execution-readiness")
    ResponseEntity<WorkflowExecutionReadinessView> pageWorkbenchExecutionReadiness(
            @PathVariable("projectCode") String projectCode,
            @RequestParam("pageKey") String pageKey,
            @RequestParam("sessionId") String sessionId,
            @RequestParam("pageInstanceId") String pageInstanceId,
            @RequestParam("traceId") String traceId,
            @RequestParam("workflowId") String workflowId,
            @RequestParam("workflowVersionId") Long workflowVersionId,
            @RequestParam("workflowVersion") String workflowVersion);

    @RequestMapping(method = RequestMethod.GET, path = "/api/workflows/credentials")
    ResponseEntity<Object> listWorkflowCredentials(@RequestParam(value = "projectId", required = false) Long projectId,
                                                   @RequestParam(value = "projectCode", required = false) String projectCode);

    @RequestMapping(method = RequestMethod.POST, path = "/api/workflows/credentials")
    ResponseEntity<Object> createWorkflowCredential(@RequestBody Map<String, Object> body);

    @RequestMapping(method = RequestMethod.PUT, path = "/api/workflows/credentials/{id}")
    ResponseEntity<Object> updateWorkflowCredential(@PathVariable("id") Long id,
                                                    @RequestBody Map<String, Object> body);

    @RequestMapping(method = RequestMethod.DELETE, path = "/api/workflows/credentials/{id}")
    ResponseEntity<Object> deleteWorkflowCredential(@PathVariable("id") Long id);

    @RequestMapping(method = RequestMethod.GET, path = "/api/runtime/evals/datasets")
    ResponseEntity<Object> listEvalDatasets(@RequestParam(value = "agentId", required = false) String agentId);

    @RequestMapping(method = RequestMethod.POST, path = "/api/runtime/evals/datasets")
    ResponseEntity<Object> createEvalDataset(@RequestBody Map<String, Object> body);

    @RequestMapping(method = RequestMethod.POST, path = "/api/runtime/evals/datasets/{datasetId}/cases/import")
    ResponseEntity<Object> importEvalCases(@PathVariable("datasetId") Long datasetId,
                                           @RequestBody Map<String, Object> body);

    @RequestMapping(method = RequestMethod.GET, path = "/api/runtime/evals/datasets/{datasetId}/cases")
    ResponseEntity<Object> listEvalCases(@PathVariable("datasetId") Long datasetId);

    @RequestMapping(method = RequestMethod.POST, path = "/api/runtime/evals/runs")
    ResponseEntity<Object> startEvalRun(@RequestBody Map<String, Object> body);

    @RequestMapping(method = RequestMethod.GET, path = "/api/runtime/evals/runs/{runId}")
    ResponseEntity<Object> getEvalRun(@PathVariable("runId") Long runId);

    @RequestMapping(method = RequestMethod.GET, path = "/api/runtime/evals/runs/{runId}/results")
    ResponseEntity<Object> listEvalRunResults(@PathVariable("runId") Long runId);

    @RequestMapping(method = RequestMethod.GET, path = "/api/runtime/evals/v2/datasets")
    ResponseEntity<Object> listEvalOpsDatasets(@RequestParam("tenantId") String tenantId,
                                               @RequestParam(value = "targetId", required = false) String targetId);

    @RequestMapping(method = RequestMethod.POST, path = "/api/runtime/evals/v2/datasets")
    ResponseEntity<Object> createEvalOpsDataset(@RequestBody Map<String, Object> body);

    @RequestMapping(method = RequestMethod.GET, path = "/api/runtime/evals/v2/datasets/{datasetId}")
    ResponseEntity<Object> getEvalOpsDataset(@PathVariable("datasetId") Long datasetId);

    @RequestMapping(method = RequestMethod.POST, path = "/api/runtime/evals/v2/datasets/{datasetId}/versions")
    ResponseEntity<Object> createEvalOpsDatasetVersion(@PathVariable("datasetId") Long datasetId,
                                                       @RequestBody Map<String, Object> body);

    @RequestMapping(method = RequestMethod.POST,
            path = "/api/runtime/evals/v2/datasets/{datasetId}/versions/from-trace")
    ResponseEntity<Object> createEvalOpsDatasetVersionFromTrace(
            @PathVariable("datasetId") Long datasetId,
            @RequestBody Map<String, Object> body);

    @RequestMapping(method = RequestMethod.GET, path = "/api/runtime/evals/v2/dataset-versions/{versionId}")
    ResponseEntity<Object> getEvalOpsDatasetVersion(@PathVariable("versionId") Long versionId);

    @RequestMapping(method = RequestMethod.GET, path = "/api/runtime/evals/v2/evaluator-suites")
    ResponseEntity<Object> listEvalOpsEvaluatorSuites(@RequestParam("tenantId") String tenantId);

    @RequestMapping(method = RequestMethod.POST, path = "/api/runtime/evals/v2/evaluator-suites")
    ResponseEntity<Object> createEvalOpsEvaluatorSuite(@RequestBody Map<String, Object> body);

    @RequestMapping(method = RequestMethod.GET, path = "/api/runtime/evals/v2/experiments")
    ResponseEntity<Object> listEvalOpsExperiments(@RequestParam("tenantId") String tenantId,
                                                  @RequestParam(value = "targetId", required = false) String targetId);

    @RequestMapping(method = RequestMethod.POST, path = "/api/runtime/evals/v2/experiments")
    ResponseEntity<Object> createEvalOpsExperiment(@RequestBody Map<String, Object> body);

    @RequestMapping(method = RequestMethod.GET, path = "/api/runtime/evals/v2/experiments/{experimentId}")
    ResponseEntity<Object> getEvalOpsExperiment(@PathVariable("experimentId") Long experimentId);

    @RequestMapping(method = RequestMethod.GET, path = "/api/runtime/evals/v2/experiments/{experimentId}/items")
    ResponseEntity<Object> listEvalOpsExperimentItems(
            @PathVariable("experimentId") Long experimentId,
            @RequestParam(value = "variantId", required = false) Long variantId,
            @RequestParam("page") int page,
            @RequestParam("pageSize") int pageSize);

    @RequestMapping(method = RequestMethod.POST, path = "/api/runtime/evals/v2/experiments/{experimentId}/cancel")
    ResponseEntity<Object> cancelEvalOpsExperiment(@PathVariable("experimentId") Long experimentId);

    @RequestMapping(method = RequestMethod.GET, path = "/api/agents")
    ResponseEntity<Object> listAgents(@RequestParam(value = "projectId", required = false) Long projectId,
                                      @RequestParam(value = "projectCode", required = false) String projectCode);

    @RequestMapping(method = RequestMethod.GET, path = "/api/agents/statistics")
    ResponseEntity<Object> getAgentStatistics(
            @RequestParam(value = "projectId", required = false) Long projectId,
            @RequestParam(value = "projectCode", required = false) String projectCode);

    @RequestMapping(method = RequestMethod.POST, path = "/api/agents")
    ResponseEntity<Object> createAgent(@RequestBody Map<String, Object> body);

    @RequestMapping(method = RequestMethod.GET, path = "/api/agents/{id}")
    ResponseEntity<Object> getAgent(@PathVariable("id") String id);

    @RequestMapping(method = RequestMethod.PUT, path = "/api/agents/{id}")
    ResponseEntity<Object> updateAgent(@PathVariable("id") String id,
                                       @RequestBody Map<String, Object> body);

    @RequestMapping(method = RequestMethod.DELETE, path = "/api/agents/{id}")
    ResponseEntity<Object> deleteAgent(@PathVariable("id") String id);

    @RequestMapping(method = RequestMethod.GET, path = "/api/agents/{agentId}/config-versions")
    ResponseEntity<Object> listAgentConfigVersions(@PathVariable("agentId") String agentId);

    @RequestMapping(method = RequestMethod.PUT, path = "/api/agents/{agentId}/config-versions/draft")
    ResponseEntity<Object> saveAgentConfigDraft(@PathVariable("agentId") String agentId,
                                                @RequestBody Map<String, Object> body);

    @RequestMapping(method = RequestMethod.POST,
            path = "/api/agents/{agentId}/config-versions/{configVersionId}/publish")
    ResponseEntity<Object> publishAgentConfigVersion(@PathVariable("agentId") String agentId,
                                                     @PathVariable("configVersionId") Long configVersionId,
                                                     @RequestBody Map<String, Object> body);

    @RequestMapping(method = RequestMethod.POST,
            path = "/api/agents/{agentId}/config-versions/{configVersionId}/copy-to-draft")
    ResponseEntity<Object> copyAgentConfigVersionToDraft(@PathVariable("agentId") String agentId,
                                                         @PathVariable("configVersionId") Long configVersionId);

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

    @RequestMapping(method = RequestMethod.GET, path = "/api/runtime/interactions/human-approvals")
    ResponseEntity<Object> listHumanApprovals(@RequestParam(value = "agentId", required = false) String agentId,
                                              @RequestParam(value = "userId", required = false) String userId,
                                              @RequestParam("limit") int limit);

    @RequestMapping(method = RequestMethod.POST, path = "/api/registry/projects/{projectCode}/agent-graphs/sync")
    ResponseEntity<Object> syncAgentGraphs(@PathVariable("projectCode") String projectCode,
                                           @RequestBody Map<String, Object> body);
}
