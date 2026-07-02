package com.enterprise.ai.control.client;

import com.enterprise.ai.control.client.capability.CapabilityProxyClient;
import com.enterprise.ai.control.client.runtime.RuntimeProxyClient;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;

import java.lang.reflect.Method;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class ControlProxyClientContractTest {

    @Test
    void runtimeProxyClientKeepsPublicRuntimeRoutesBehindControl() throws Exception {
        FeignClient feignClient = RuntimeProxyClient.class.getAnnotation(FeignClient.class);
        assertEquals("reachai-runtime-proxy", feignClient.name());
        assertEquals("${services.runtime-service.url:http://localhost:18604}", feignClient.url());

        Method executeAgent = RuntimeProxyClient.class.getMethod("executeAgent", Map.class);
        assertMapping(executeAgent, RequestMethod.POST, "/api/agent/execute");
        assertEquals(ResponseEntity.class, executeAgent.getReturnType());

        Method executeAgentDetailed = RuntimeProxyClient.class.getMethod("executeAgentDetailed", Map.class);
        assertMapping(executeAgentDetailed, RequestMethod.POST, "/api/agent/execute/detailed");
        assertEquals(ResponseEntity.class, executeAgentDetailed.getReturnType());

        Method routeEvaluation = RuntimeProxyClient.class.getMethod("routeEvaluation", int.class);
        assertMapping(routeEvaluation, RequestMethod.GET, "/api/agent/route-evaluation");
        assertEquals(ResponseEntity.class, routeEvaluation.getReturnType());

        Method getTrace = RuntimeProxyClient.class.getMethod("getTrace", String.class);
        assertMapping(getTrace, RequestMethod.GET, "/api/traces/{traceId}");
        assertEquals(ResponseEntity.class, getTrace.getReturnType());

        Method listRecentTraces = RuntimeProxyClient.class.getMethod("listRecentTraces", String.class, int.class, int.class);
        assertMapping(listRecentTraces, RequestMethod.GET, "/api/traces/recent");
        assertEquals(ResponseEntity.class, listRecentTraces.getReturnType());

        Method runOpsDetail = RuntimeProxyClient.class.getMethod("runOpsDetail", String.class);
        assertMapping(runOpsDetail, RequestMethod.GET, "/api/runops/traces/{traceId}");
        assertEquals(ResponseEntity.class, runOpsDetail.getReturnType());

        Method runOpsRecent = RuntimeProxyClient.class.getMethod("runOpsRecent", String.class, int.class, int.class);
        assertMapping(runOpsRecent, RequestMethod.GET, "/api/runops/traces/recent");
        assertEquals(ResponseEntity.class, runOpsRecent.getReturnType());

        Method runOpsDiagnostics = RuntimeProxyClient.class.getMethod("runOpsDiagnostics", String.class, int.class, int.class);
        assertMapping(runOpsDiagnostics, RequestMethod.GET, "/api/runops/diagnostics");
        assertEquals(ResponseEntity.class, runOpsDiagnostics.getReturnType());

        Method runOpsCompare = RuntimeProxyClient.class.getMethod("runOpsCompare", String.class, String.class);
        assertMapping(runOpsCompare, RequestMethod.GET, "/api/runops/traces/{traceId}/compare/{candidateTraceId}");
        assertEquals(ResponseEntity.class, runOpsCompare.getReturnType());

        Method runOpsReplay = RuntimeProxyClient.class.getMethod("runOpsReplay", String.class, Map.class);
        assertMapping(runOpsReplay, RequestMethod.POST, "/api/runops/traces/{traceId}/replay");
        assertEquals(ResponseEntity.class, runOpsReplay.getReturnType());

        Method chat = RuntimeProxyClient.class.getMethod("chat", Map.class);
        assertMapping(chat, RequestMethod.POST, "/api/chat");
        assertEquals(ResponseEntity.class, chat.getReturnType());

        Method chatStream = RuntimeProxyClient.class.getMethod("chatStream", Map.class);
        assertMapping(chatStream, RequestMethod.POST, "/api/chat/stream");
        assertEquals(ResponseEntity.class, chatStream.getReturnType());

        Method clearChatSession = RuntimeProxyClient.class.getMethod("clearChatSession", String.class);
        assertMapping(clearChatSession, RequestMethod.DELETE, "/api/chat/session/{sessionId}");
        assertEquals(ResponseEntity.class, clearChatSession.getReturnType());

        Method listWorkflows = RuntimeProxyClient.class
                .getMethod("listWorkflows", Long.class, String.class, String.class, String.class);
        assertMapping(listWorkflows, RequestMethod.GET, "/api/workflows");
        assertEquals(ResponseEntity.class, listWorkflows.getReturnType());

        Method createWorkflow = RuntimeProxyClient.class.getMethod("createWorkflow", Map.class);
        assertMapping(createWorkflow, RequestMethod.POST, "/api/workflows");
        assertEquals(ResponseEntity.class, createWorkflow.getReturnType());

        Method getWorkflow = RuntimeProxyClient.class.getMethod("getWorkflow", String.class);
        assertMapping(getWorkflow, RequestMethod.GET, "/api/workflows/{id}");
        assertEquals(ResponseEntity.class, getWorkflow.getReturnType());

        Method updateWorkflow = RuntimeProxyClient.class.getMethod("updateWorkflow", String.class, Map.class);
        assertMapping(updateWorkflow, RequestMethod.PUT, "/api/workflows/{id}");
        assertEquals(ResponseEntity.class, updateWorkflow.getReturnType());

        Method deleteWorkflow = RuntimeProxyClient.class.getMethod("deleteWorkflow", String.class);
        assertMapping(deleteWorkflow, RequestMethod.DELETE, "/api/workflows/{id}");
        assertEquals(ResponseEntity.class, deleteWorkflow.getReturnType());

        Method graphNodeTypes = RuntimeProxyClient.class.getMethod("graphNodeTypes");
        assertMapping(graphNodeTypes, RequestMethod.GET, "/api/workflows/graph-node-types");
        assertEquals(ResponseEntity.class, graphNodeTypes.getReturnType());

        Method validateWorkflowRuntime = RuntimeProxyClient.class.getMethod("validateWorkflowRuntime", Map.class);
        assertMapping(validateWorkflowRuntime, RequestMethod.POST, "/api/workflows/runtime-validation");
        assertEquals(ResponseEntity.class, validateWorkflowRuntime.getReturnType());

        Method workflowStudio = RuntimeProxyClient.class.getMethod("workflowStudio", String.class);
        assertMapping(workflowStudio, RequestMethod.GET, "/api/workflows/{id}/studio");
        assertEquals(ResponseEntity.class, workflowStudio.getReturnType());

        Method saveWorkflowStudio = RuntimeProxyClient.class.getMethod("saveWorkflowStudio", String.class, Map.class);
        assertMapping(saveWorkflowStudio, RequestMethod.PUT, "/api/workflows/{id}/studio");
        assertEquals(ResponseEntity.class, saveWorkflowStudio.getReturnType());

        Method debugWorkflowNode = RuntimeProxyClient.class.getMethod("debugWorkflowNode", Map.class);
        assertMapping(debugWorkflowNode, RequestMethod.POST, "/api/workflows/studio/debug-node");
        assertEquals(ResponseEntity.class, debugWorkflowNode.getReturnType());

        Method debugWorkflowRun = RuntimeProxyClient.class.getMethod("debugWorkflowRun", Map.class);
        assertMapping(debugWorkflowRun, RequestMethod.POST, "/api/workflows/studio/debug-run");
        assertEquals(ResponseEntity.class, debugWorkflowRun.getReturnType());

        Method generateWorkflowStudioDraft = RuntimeProxyClient.class
                .getMethod("generateWorkflowStudioDraft", Map.class);
        assertMapping(generateWorkflowStudioDraft, RequestMethod.POST, "/api/workflows/studio/generate-draft");
        assertEquals(ResponseEntity.class, generateWorkflowStudioDraft.getReturnType());

        Method editWorkflowStudioDraft = RuntimeProxyClient.class
                .getMethod("editWorkflowStudioDraft", Map.class);
        assertMapping(editWorkflowStudioDraft, RequestMethod.POST, "/api/workflows/studio/edit-draft");
        assertEquals(ResponseEntity.class, editWorkflowStudioDraft.getReturnType());

        Method createWorkflowAiCodingWorkflow = RuntimeProxyClient.class
                .getMethod("createWorkflowAiCodingWorkflow", Map.class);
        assertMapping(createWorkflowAiCodingWorkflow, RequestMethod.POST, "/api/workflows/ai-coding/workflows");
        assertEquals(ResponseEntity.class, createWorkflowAiCodingWorkflow.getReturnType());

        Method workflowAiCodingContext = RuntimeProxyClient.class
                .getMethod("workflowAiCodingContext", String.class);
        assertMapping(workflowAiCodingContext, RequestMethod.GET, "/api/workflows/{workflowId}/ai-coding/context");
        assertEquals(ResponseEntity.class, workflowAiCodingContext.getReturnType());

        Method validateWorkflowAiCoding = RuntimeProxyClient.class
                .getMethod("validateWorkflowAiCoding", String.class, Map.class);
        assertMapping(validateWorkflowAiCoding, RequestMethod.POST, "/api/workflows/{workflowId}/ai-coding/validate");
        assertEquals(ResponseEntity.class, validateWorkflowAiCoding.getReturnType());

        Method patchWorkflowAiCoding = RuntimeProxyClient.class
                .getMethod("patchWorkflowAiCoding", String.class, Map.class);
        assertMapping(patchWorkflowAiCoding, RequestMethod.POST, "/api/workflows/{workflowId}/ai-coding/patch");
        assertEquals(ResponseEntity.class, patchWorkflowAiCoding.getReturnType());

        Method runWorkflowAiCoding = RuntimeProxyClient.class
                .getMethod("runWorkflowAiCoding", String.class, Map.class);
        assertMapping(runWorkflowAiCoding, RequestMethod.POST, "/api/workflows/{workflowId}/ai-coding/run");
        assertEquals(ResponseEntity.class, runWorkflowAiCoding.getReturnType());

        Method workflowAiCodingVersions = RuntimeProxyClient.class
                .getMethod("workflowAiCodingVersions", String.class);
        assertMapping(workflowAiCodingVersions, RequestMethod.GET, "/api/workflows/{workflowId}/ai-coding/versions");
        assertEquals(ResponseEntity.class, workflowAiCodingVersions.getReturnType());

        Method publishWorkflowAiCoding = RuntimeProxyClient.class
                .getMethod("publishWorkflowAiCoding", String.class, Map.class);
        assertMapping(publishWorkflowAiCoding, RequestMethod.POST, "/api/workflows/{workflowId}/ai-coding/publish");
        assertEquals(ResponseEntity.class, publishWorkflowAiCoding.getReturnType());

        Method workflowAiCodingRuns = RuntimeProxyClient.class
                .getMethod("workflowAiCodingRuns", String.class, Integer.class, Integer.class);
        assertMapping(workflowAiCodingRuns, RequestMethod.GET, "/api/workflows/{workflowId}/ai-coding/runs");
        assertEquals(ResponseEntity.class, workflowAiCodingRuns.getReturnType());

        Method workflowAiCodingRunDetail = RuntimeProxyClient.class
                .getMethod("workflowAiCodingRunDetail", String.class, String.class);
        assertMapping(workflowAiCodingRunDetail, RequestMethod.GET,
                "/api/workflows/{workflowId}/ai-coding/runs/{traceId}");
        assertEquals(ResponseEntity.class, workflowAiCodingRunDetail.getReturnType());

        Method workflowAiCodingPageAssistantCatalog = RuntimeProxyClient.class
                .getMethod("workflowAiCodingPageAssistantCatalog", String.class);
        assertMapping(workflowAiCodingPageAssistantCatalog, RequestMethod.GET,
                "/api/workflows/{workflowId}/ai-coding/page-assistant/catalog");
        assertEquals(ResponseEntity.class, workflowAiCodingPageAssistantCatalog.getReturnType());

        Method validateWorkflowAiCodingPageAssistant = RuntimeProxyClient.class
                .getMethod("validateWorkflowAiCodingPageAssistant", String.class, Map.class);
        assertMapping(validateWorkflowAiCodingPageAssistant, RequestMethod.POST,
                "/api/workflows/{workflowId}/ai-coding/page-assistant/validate");
        assertEquals(ResponseEntity.class, validateWorkflowAiCodingPageAssistant.getReturnType());

        Method smokeTestWorkflowAiCodingPageAssistant = RuntimeProxyClient.class
                .getMethod("smokeTestWorkflowAiCodingPageAssistant", String.class, Map.class);
        assertMapping(smokeTestWorkflowAiCodingPageAssistant, RequestMethod.POST,
                "/api/workflows/{workflowId}/ai-coding/page-assistant/smoke-test");
        assertEquals(ResponseEntity.class, smokeTestWorkflowAiCodingPageAssistant.getReturnType());

        Method listWorkflowVersions = RuntimeProxyClient.class.getMethod("listWorkflowVersions", String.class);
        assertMapping(listWorkflowVersions, RequestMethod.GET, "/api/workflows/{workflowId}/versions");
        assertEquals(ResponseEntity.class, listWorkflowVersions.getReturnType());

        Method publishWorkflowVersion = RuntimeProxyClient.class
                .getMethod("publishWorkflowVersion", String.class, Map.class);
        assertMapping(publishWorkflowVersion, RequestMethod.POST, "/api/workflows/{workflowId}/versions");
        assertEquals(ResponseEntity.class, publishWorkflowVersion.getReturnType());

        Method publishWorkflowVersionExplicit = RuntimeProxyClient.class
                .getMethod("publishWorkflowVersionExplicit", String.class, Map.class);
        assertMapping(publishWorkflowVersionExplicit, RequestMethod.POST, "/api/workflows/{workflowId}/versions/publish");
        assertEquals(ResponseEntity.class, publishWorkflowVersionExplicit.getReturnType());

        Method validateWorkflowVersion = RuntimeProxyClient.class.getMethod("validateWorkflowVersion", String.class);
        assertMapping(validateWorkflowVersion, RequestMethod.POST, "/api/workflows/{workflowId}/versions/validate");
        assertEquals(ResponseEntity.class, validateWorkflowVersion.getReturnType());

        Method rollbackWorkflowVersion = RuntimeProxyClient.class
                .getMethod("rollbackWorkflowVersion", String.class, Long.class, Map.class);
        assertMapping(rollbackWorkflowVersion, RequestMethod.POST,
                "/api/workflows/{workflowId}/versions/{versionId}/rollback");
        assertEquals(ResponseEntity.class, rollbackWorkflowVersion.getReturnType());

        Method bindPageAssistantWorkflow = RuntimeProxyClient.class
                .getMethod("bindPageAssistantWorkflow", String.class, Map.class);
        assertMapping(bindPageAssistantWorkflow, RequestMethod.POST, "/api/workflows/{id}/page-assistant/bind");
        assertEquals(ResponseEntity.class, bindPageAssistantWorkflow.getReturnType());

        Method listWorkflowCredentials = RuntimeProxyClient.class
                .getMethod("listWorkflowCredentials", Long.class, String.class);
        assertMapping(listWorkflowCredentials, RequestMethod.GET, "/api/agent/workflow-credentials");
        assertEquals(ResponseEntity.class, listWorkflowCredentials.getReturnType());

        Method createWorkflowCredential = RuntimeProxyClient.class.getMethod("createWorkflowCredential", Map.class);
        assertMapping(createWorkflowCredential, RequestMethod.POST, "/api/agent/workflow-credentials");
        assertEquals(ResponseEntity.class, createWorkflowCredential.getReturnType());

        Method updateWorkflowCredential = RuntimeProxyClient.class
                .getMethod("updateWorkflowCredential", Long.class, Map.class);
        assertMapping(updateWorkflowCredential, RequestMethod.PUT, "/api/agent/workflow-credentials/{id}");
        assertEquals(ResponseEntity.class, updateWorkflowCredential.getReturnType());

        Method deleteWorkflowCredential = RuntimeProxyClient.class.getMethod("deleteWorkflowCredential", Long.class);
        assertMapping(deleteWorkflowCredential, RequestMethod.DELETE, "/api/agent/workflow-credentials/{id}");
        assertEquals(ResponseEntity.class, deleteWorkflowCredential.getReturnType());

        Method listEvalDatasets = RuntimeProxyClient.class.getMethod("listEvalDatasets", String.class);
        assertMapping(listEvalDatasets, RequestMethod.GET, "/api/agent/evals/datasets");
        assertEquals(ResponseEntity.class, listEvalDatasets.getReturnType());

        Method createEvalDataset = RuntimeProxyClient.class.getMethod("createEvalDataset", Map.class);
        assertMapping(createEvalDataset, RequestMethod.POST, "/api/agent/evals/datasets");
        assertEquals(ResponseEntity.class, createEvalDataset.getReturnType());

        Method importEvalCases = RuntimeProxyClient.class.getMethod("importEvalCases", Long.class, Map.class);
        assertMapping(importEvalCases, RequestMethod.POST, "/api/agent/evals/datasets/{datasetId}/cases/import");
        assertEquals(ResponseEntity.class, importEvalCases.getReturnType());

        Method listEvalCases = RuntimeProxyClient.class.getMethod("listEvalCases", Long.class);
        assertMapping(listEvalCases, RequestMethod.GET, "/api/agent/evals/datasets/{datasetId}/cases");
        assertEquals(ResponseEntity.class, listEvalCases.getReturnType());

        Method startEvalRun = RuntimeProxyClient.class.getMethod("startEvalRun", Map.class);
        assertMapping(startEvalRun, RequestMethod.POST, "/api/agent/evals/runs");
        assertEquals(ResponseEntity.class, startEvalRun.getReturnType());

        Method getEvalRun = RuntimeProxyClient.class.getMethod("getEvalRun", Long.class);
        assertMapping(getEvalRun, RequestMethod.GET, "/api/agent/evals/runs/{runId}");
        assertEquals(ResponseEntity.class, getEvalRun.getReturnType());

        Method listEvalRunResults = RuntimeProxyClient.class.getMethod("listEvalRunResults", Long.class);
        assertMapping(listEvalRunResults, RequestMethod.GET, "/api/agent/evals/runs/{runId}/results");
        assertEquals(ResponseEntity.class, listEvalRunResults.getReturnType());

        Method listAgents = RuntimeProxyClient.class.getMethod("listAgents", Long.class, String.class, String.class);
        assertMapping(listAgents, RequestMethod.GET, "/api/agents");
        assertEquals(ResponseEntity.class, listAgents.getReturnType());

        Method createAgent = RuntimeProxyClient.class.getMethod("createAgent", Map.class);
        assertMapping(createAgent, RequestMethod.POST, "/api/agents");
        assertEquals(ResponseEntity.class, createAgent.getReturnType());

        Method getAgent = RuntimeProxyClient.class.getMethod("getAgent", String.class);
        assertMapping(getAgent, RequestMethod.GET, "/api/agents/{id}");
        assertEquals(ResponseEntity.class, getAgent.getReturnType());

        Method updateAgent = RuntimeProxyClient.class.getMethod("updateAgent", String.class, Map.class);
        assertMapping(updateAgent, RequestMethod.PUT, "/api/agents/{id}");
        assertEquals(ResponseEntity.class, updateAgent.getReturnType());

        Method deleteAgent = RuntimeProxyClient.class.getMethod("deleteAgent", String.class);
        assertMapping(deleteAgent, RequestMethod.DELETE, "/api/agents/{id}");
        assertEquals(ResponseEntity.class, deleteAgent.getReturnType());

        Method listAgentWorkflowBindings = RuntimeProxyClient.class
                .getMethod("listAgentWorkflowBindings", String.class);
        assertMapping(listAgentWorkflowBindings, RequestMethod.GET, "/api/agents/{agentId}/workflow-bindings");
        assertEquals(ResponseEntity.class, listAgentWorkflowBindings.getReturnType());

        Method createAgentWorkflowBinding = RuntimeProxyClient.class
                .getMethod("createAgentWorkflowBinding", String.class, Map.class);
        assertMapping(createAgentWorkflowBinding, RequestMethod.POST, "/api/agents/{agentId}/workflow-bindings");
        assertEquals(ResponseEntity.class, createAgentWorkflowBinding.getReturnType());

        Method updateAgentWorkflowBinding = RuntimeProxyClient.class
                .getMethod("updateAgentWorkflowBinding", String.class, Long.class, Map.class);
        assertMapping(updateAgentWorkflowBinding, RequestMethod.PUT,
                "/api/agents/{agentId}/workflow-bindings/{bindingId}");
        assertEquals(ResponseEntity.class, updateAgentWorkflowBinding.getReturnType());

        Method deleteAgentWorkflowBinding = RuntimeProxyClient.class
                .getMethod("deleteAgentWorkflowBinding", String.class, Long.class);
        assertMapping(deleteAgentWorkflowBinding, RequestMethod.DELETE,
                "/api/agents/{agentId}/workflow-bindings/{bindingId}");
        assertEquals(ResponseEntity.class, deleteAgentWorkflowBinding.getReturnType());

        Method resolveAgentWorkflowBindingPreview = RuntimeProxyClient.class
                .getMethod("resolveAgentWorkflowBindingPreview", String.class, Map.class);
        assertMapping(resolveAgentWorkflowBindingPreview, RequestMethod.POST,
                "/api/agents/{agentId}/workflow-bindings/resolve-preview");
        assertEquals(ResponseEntity.class, resolveAgentWorkflowBindingPreview.getReturnType());

        Method listRuntimes = RuntimeProxyClient.class.getMethod("listRuntimes");
        assertMapping(listRuntimes, RequestMethod.GET, "/api/runtimes");
        assertEquals(ResponseEntity.class, listRuntimes.getReturnType());

        Method dispatchEmbeddedRuntime = RuntimeProxyClient.class.getMethod("dispatchEmbeddedRuntime", Map.class);
        assertMapping(dispatchEmbeddedRuntime, RequestMethod.POST, "/api/runtimes/embedded/dispatch");
        assertEquals(ResponseEntity.class, dispatchEmbeddedRuntime.getReturnType());

        Method executeRuntimeTool = RuntimeProxyClient.class.getMethod("executeRuntimeTool", String.class, Map.class);
        assertMapping(executeRuntimeTool, RequestMethod.POST, "/api/runtime/tools/{qualifiedName}/execute");
        assertEquals(ResponseEntity.class, executeRuntimeTool.getReturnType());

        Method executeRuntimeComposition = RuntimeProxyClient.class
                .getMethod("executeRuntimeComposition", String.class, Map.class);
        assertMapping(executeRuntimeComposition, RequestMethod.POST,
                "/api/runtime/compositions/{qualifiedName}/execute");
        assertEquals(ResponseEntity.class, executeRuntimeComposition.getReturnType());

        Method resumeRuntimeInteraction = RuntimeProxyClient.class
                .getMethod("resumeRuntimeInteraction", String.class, Map.class);
        assertMapping(resumeRuntimeInteraction, RequestMethod.POST,
                "/api/runtime/interactions/{sessionId}/resume");
        assertEquals(ResponseEntity.class, resumeRuntimeInteraction.getReturnType());

        Method createRuntimeDebugSession = RuntimeProxyClient.class
                .getMethod("createRuntimeDebugSession", Map.class);
        assertMapping(createRuntimeDebugSession, RequestMethod.POST, "/api/runtime/debug-sessions");
        assertEquals(ResponseEntity.class, createRuntimeDebugSession.getReturnType());

        Method getRuntimeDebugSession = RuntimeProxyClient.class
                .getMethod("getRuntimeDebugSession", String.class);
        assertMapping(getRuntimeDebugSession, RequestMethod.GET, "/api/runtime/debug-sessions/{sessionId}");
        assertEquals(ResponseEntity.class, getRuntimeDebugSession.getReturnType());

        Method submitRuntimeDebugSession = RuntimeProxyClient.class
                .getMethod("submitRuntimeDebugSession", String.class, Map.class);
        assertMapping(submitRuntimeDebugSession, RequestMethod.POST, "/api/runtime/debug-sessions/{sessionId}/submit");
        assertEquals(ResponseEntity.class, submitRuntimeDebugSession.getReturnType());

        Method cancelRuntimeDebugSession = RuntimeProxyClient.class
                .getMethod("cancelRuntimeDebugSession", String.class);
        assertMapping(cancelRuntimeDebugSession, RequestMethod.POST, "/api/runtime/debug-sessions/{sessionId}/cancel");
        assertEquals(ResponseEntity.class, cancelRuntimeDebugSession.getReturnType());

        Method listHumanApprovals = RuntimeProxyClient.class
                .getMethod("listHumanApprovals", Long.class, String.class, int.class);
        assertMapping(listHumanApprovals, RequestMethod.GET, "/api/agent/interactions/human-approvals");
        assertEquals(ResponseEntity.class, listHumanApprovals.getReturnType());

        Method submitHumanApproval = RuntimeProxyClient.class
                .getMethod("submitHumanApproval", String.class, Map.class);
        assertMapping(submitHumanApproval, RequestMethod.POST,
                "/api/agent/interactions/human-approvals/{interactionId}/submit");
        assertEquals(ResponseEntity.class, submitHumanApproval.getReturnType());

        Method cancelHumanApproval = RuntimeProxyClient.class
                .getMethod("cancelHumanApproval", String.class, String.class);
        assertMapping(cancelHumanApproval, RequestMethod.DELETE,
                "/api/agent/interactions/human-approvals/{interactionId}");
        assertEquals(ResponseEntity.class, cancelHumanApproval.getReturnType());

        Method syncAgentGraphs = RuntimeProxyClient.class.getMethod("syncAgentGraphs", String.class, Map.class);
        assertMapping(syncAgentGraphs, RequestMethod.POST, "/api/registry/projects/{projectCode}/agent-graphs/sync");
        assertEquals(ResponseEntity.class, syncAgentGraphs.getReturnType());
    }

    @Test
    void capabilityProxyClientKeepsPublicRegistryRoutesBehindControl() throws Exception {
        FeignClient feignClient = CapabilityProxyClient.class.getAnnotation(FeignClient.class);
        assertEquals("reachai-capability-proxy", feignClient.name());
        assertEquals("${services.capability-service.url:http://localhost:18605}", feignClient.url());

        Method registerProject = CapabilityProxyClient.class.getMethod("registerProject", Map.class);
        assertMapping(registerProject, RequestMethod.POST, "/api/registry/projects/register");
        assertEquals(ResponseEntity.class, registerProject.getReturnType());

        Method listInstances = CapabilityProxyClient.class.getMethod("listInstances", String.class);
        assertMapping(listInstances, RequestMethod.GET, "/api/registry/projects/{projectCode}/instances");
        assertEquals(ResponseEntity.class, listInstances.getReturnType());

        Method heartbeat = CapabilityProxyClient.class.getMethod("heartbeat", String.class, Map.class);
        assertMapping(heartbeat, RequestMethod.POST, "/api/registry/projects/{projectCode}/instances/heartbeat");
        assertEquals(ResponseEntity.class, heartbeat.getReturnType());

        Method syncCapabilities = CapabilityProxyClient.class.getMethod("syncCapabilities", String.class, Map.class);
        assertMapping(syncCapabilities, RequestMethod.POST, "/api/registry/projects/{projectCode}/capabilities/sync");
        assertEquals(ResponseEntity.class, syncCapabilities.getReturnType());

        Method diffCapabilities = CapabilityProxyClient.class.getMethod("diffCapabilities", String.class, Map.class);
        assertMapping(diffCapabilities, RequestMethod.POST, "/api/registry/projects/{projectCode}/capabilities/diff");
        assertEquals(ResponseEntity.class, diffCapabilities.getReturnType());

        Method getToolDefinition = CapabilityProxyClient.class.getMethod("getToolDefinition", String.class);
        assertMapping(getToolDefinition, RequestMethod.GET, "/internal/capability/tools/{qualifiedName}");
        assertEquals(ResponseEntity.class, getToolDefinition.getReturnType());

        Method applyCapabilities = CapabilityProxyClient.class.getMethod("applyCapabilities", String.class, Map.class);
        assertMapping(applyCapabilities, RequestMethod.POST, "/api/registry/projects/{projectCode}/capabilities/apply");
        assertEquals(ResponseEntity.class, applyCapabilities.getReturnType());

        Method listCapabilitySnapshots = CapabilityProxyClient.class.getMethod("listCapabilitySnapshots", String.class);
        assertMapping(listCapabilitySnapshots, RequestMethod.GET, "/api/registry/projects/{projectCode}/capability-snapshots");
        assertEquals(ResponseEntity.class, listCapabilitySnapshots.getReturnType());

        Method listCapabilityDiffItems = CapabilityProxyClient.class.getMethod("listCapabilityDiffItems", Long.class);
        assertMapping(listCapabilityDiffItems, RequestMethod.GET, "/api/registry/capability-snapshots/{snapshotId}/diff-items");
        assertEquals(ResponseEntity.class, listCapabilityDiffItems.getReturnType());

        Method reviewCapabilityDiffItem = CapabilityProxyClient.class.getMethod("reviewCapabilityDiffItem", Long.class, Map.class);
        assertMapping(reviewCapabilityDiffItem, RequestMethod.POST, "/api/registry/capability-diff-items/{diffItemId}/review");
        assertEquals(ResponseEntity.class, reviewCapabilityDiffItem.getReturnType());

        Method listEmbedCredentialPolicies = CapabilityProxyClient.class
                .getMethod("listEmbedCredentialPolicies", String.class, String.class, int.class);
        assertMapping(listEmbedCredentialPolicies, RequestMethod.GET, "/internal/capability/embed/credentials");
        assertEquals(ResponseEntity.class, listEmbedCredentialPolicies.getReturnType());

        Method updateEmbedCredentialPolicy = CapabilityProxyClient.class
                .getMethod("updateEmbedCredentialPolicy", Long.class, Map.class);
        assertMapping(updateEmbedCredentialPolicy, RequestMethod.PUT, "/internal/capability/embed/credentials/{id}/policy");
        assertEquals(ResponseEntity.class, updateEmbedCredentialPolicy.getReturnType());

        Method verifyEmbedTokenExchange = CapabilityProxyClient.class
                .getMethod("verifyEmbedTokenExchange", String.class, String.class, String.class, String.class, Map.class);
        assertMapping(verifyEmbedTokenExchange, RequestMethod.POST, "/internal/capability/embed/token/exchange/verify");
        assertEquals(ResponseEntity.class, verifyEmbedTokenExchange.getReturnType());
    }

    private void assertMapping(Method method, RequestMethod expectedMethod, String expectedPath) {
        RequestMapping mapping = method.getAnnotation(RequestMapping.class);
        assertArrayEquals(new RequestMethod[] {expectedMethod}, mapping.method());
        assertArrayEquals(new String[] {expectedPath}, mapping.path());
    }
}
