package com.enterprise.ai.control.compat;

import com.enterprise.ai.control.client.runtime.RuntimeProxyClient;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;

import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RuntimeCompatibilityControllerTest {

    @Test
    void keepsPublicRuntimeRouteShapeOnControlService() throws Exception {
        Method executeAgent = RuntimeCompatibilityController.class.getDeclaredMethod("executeAgent", Map.class);
        Method executeAgentDetailed = RuntimeCompatibilityController.class
                .getDeclaredMethod("executeAgentDetailed", Map.class);
        Method routeEvaluation = RuntimeCompatibilityController.class.getDeclaredMethod("routeEvaluation", int.class);
        Method getTrace = RuntimeCompatibilityController.class.getDeclaredMethod("getTrace", String.class);
        Method listRecentTraces = RuntimeCompatibilityController.class
                .getDeclaredMethod("listRecentTraces", String.class, int.class, int.class);
        Method runOpsDetail = RuntimeCompatibilityController.class.getDeclaredMethod("runOpsDetail", String.class);
        Method runOpsRecent = RuntimeCompatibilityController.class
                .getDeclaredMethod("runOpsRecent", String.class, int.class, int.class);
        Method runOpsDiagnostics = RuntimeCompatibilityController.class
                .getDeclaredMethod("runOpsDiagnostics", String.class, int.class, int.class);
        Method runOpsCompare = RuntimeCompatibilityController.class
                .getDeclaredMethod("runOpsCompare", String.class, String.class);
        Method runOpsReplay = RuntimeCompatibilityController.class
                .getDeclaredMethod("runOpsReplay", String.class, Map.class);
        Method chat = RuntimeCompatibilityController.class.getDeclaredMethod("chat", Map.class);
        Method gatewayAgentChat = RuntimeCompatibilityController.class
                .getDeclaredMethod("gatewayAgentChat", String.class, Map.class);
        Method gatewayCatalog = RuntimeCompatibilityController.class
                .getDeclaredMethod("gatewayCatalog", Long.class);
        Method chatStream = RuntimeCompatibilityController.class.getDeclaredMethod("chatStream", Map.class);
        Method clearChatSession = RuntimeCompatibilityController.class
                .getDeclaredMethod("clearChatSession", String.class);
        Method listWorkflows = RuntimeCompatibilityController.class
                .getDeclaredMethod("listWorkflows", Long.class, String.class, String.class, String.class);
        Method createWorkflow = RuntimeCompatibilityController.class
                .getDeclaredMethod("createWorkflow", Map.class);
        Method getWorkflow = RuntimeCompatibilityController.class.getDeclaredMethod("getWorkflow", String.class);
        Method updateWorkflow = RuntimeCompatibilityController.class
                .getDeclaredMethod("updateWorkflow", String.class, Map.class);
        Method deleteWorkflow = RuntimeCompatibilityController.class.getDeclaredMethod("deleteWorkflow", String.class);
        Method graphNodeTypes = RuntimeCompatibilityController.class.getDeclaredMethod("graphNodeTypes");
        Method validateWorkflowRuntime = RuntimeCompatibilityController.class
                .getDeclaredMethod("validateWorkflowRuntime", Map.class);
        Method workflowStudio = RuntimeCompatibilityController.class.getDeclaredMethod("workflowStudio", String.class);
        Method saveWorkflowStudio = RuntimeCompatibilityController.class
                .getDeclaredMethod("saveWorkflowStudio", String.class, Map.class);
        Method debugWorkflowNode = RuntimeCompatibilityController.class
                .getDeclaredMethod("debugWorkflowNode", Map.class);
        Method debugWorkflowRun = RuntimeCompatibilityController.class
                .getDeclaredMethod("debugWorkflowRun", Map.class);
        Method generateWorkflowStudioDraft = RuntimeCompatibilityController.class
                .getDeclaredMethod("generateWorkflowStudioDraft", Map.class);
        Method editWorkflowStudioDraft = RuntimeCompatibilityController.class
                .getDeclaredMethod("editWorkflowStudioDraft", Map.class);
        Method createWorkflowAiCodingWorkflow = RuntimeCompatibilityController.class
                .getDeclaredMethod("createWorkflowAiCodingWorkflow", Map.class);
        Method workflowAiCodingContext = RuntimeCompatibilityController.class
                .getDeclaredMethod("workflowAiCodingContext", String.class);
        Method validateWorkflowAiCoding = RuntimeCompatibilityController.class
                .getDeclaredMethod("validateWorkflowAiCoding", String.class, Map.class);
        Method patchWorkflowAiCoding = RuntimeCompatibilityController.class
                .getDeclaredMethod("patchWorkflowAiCoding", String.class, Map.class);
        Method runWorkflowAiCoding = RuntimeCompatibilityController.class
                .getDeclaredMethod("runWorkflowAiCoding", String.class, Map.class);
        Method workflowAiCodingVersions = RuntimeCompatibilityController.class
                .getDeclaredMethod("workflowAiCodingVersions", String.class);
        Method publishWorkflowAiCoding = RuntimeCompatibilityController.class
                .getDeclaredMethod("publishWorkflowAiCoding", String.class, Map.class);
        Method workflowAiCodingRuns = RuntimeCompatibilityController.class
                .getDeclaredMethod("workflowAiCodingRuns", String.class, Integer.class, Integer.class);
        Method workflowAiCodingRunDetail = RuntimeCompatibilityController.class
                .getDeclaredMethod("workflowAiCodingRunDetail", String.class, String.class);
        Method workflowAiCodingPageAssistantCatalog = RuntimeCompatibilityController.class
                .getDeclaredMethod("workflowAiCodingPageAssistantCatalog", String.class);
        Method validateWorkflowAiCodingPageAssistant = RuntimeCompatibilityController.class
                .getDeclaredMethod("validateWorkflowAiCodingPageAssistant", String.class, Map.class);
        Method smokeTestWorkflowAiCodingPageAssistant = RuntimeCompatibilityController.class
                .getDeclaredMethod("smokeTestWorkflowAiCodingPageAssistant", String.class, Map.class);
        Method listWorkflowVersions = RuntimeCompatibilityController.class
                .getDeclaredMethod("listWorkflowVersions", String.class);
        Method publishWorkflowVersion = RuntimeCompatibilityController.class
                .getDeclaredMethod("publishWorkflowVersion", String.class, Map.class);
        Method publishWorkflowVersionExplicit = RuntimeCompatibilityController.class
                .getDeclaredMethod("publishWorkflowVersionExplicit", String.class, Map.class);
        Method validateWorkflowVersion = RuntimeCompatibilityController.class
                .getDeclaredMethod("validateWorkflowVersion", String.class);
        Method rollbackWorkflowVersion = RuntimeCompatibilityController.class
                .getDeclaredMethod("rollbackWorkflowVersion", String.class, Long.class, Map.class);
        Method bindPageAssistantWorkflow = RuntimeCompatibilityController.class
                .getDeclaredMethod("bindPageAssistantWorkflow", String.class, Map.class);
        Method listWorkflowCredentials = RuntimeCompatibilityController.class
                .getDeclaredMethod("listWorkflowCredentials", Long.class, String.class);
        Method createWorkflowCredential = RuntimeCompatibilityController.class
                .getDeclaredMethod("createWorkflowCredential", Map.class);
        Method updateWorkflowCredential = RuntimeCompatibilityController.class
                .getDeclaredMethod("updateWorkflowCredential", Long.class, Map.class);
        Method deleteWorkflowCredential = RuntimeCompatibilityController.class
                .getDeclaredMethod("deleteWorkflowCredential", Long.class);
        Method listAgents = RuntimeCompatibilityController.class
                .getDeclaredMethod("listAgents", Long.class, String.class, String.class);
        Method createAgent = RuntimeCompatibilityController.class.getDeclaredMethod("createAgent", Map.class);
        Method getAgent = RuntimeCompatibilityController.class.getDeclaredMethod("getAgent", String.class);
        Method updateAgent = RuntimeCompatibilityController.class.getDeclaredMethod("updateAgent", String.class, Map.class);
        Method deleteAgent = RuntimeCompatibilityController.class.getDeclaredMethod("deleteAgent", String.class);
        Method listAgentWorkflowBindings = RuntimeCompatibilityController.class
                .getDeclaredMethod("listAgentWorkflowBindings", String.class);
        Method createAgentWorkflowBinding = RuntimeCompatibilityController.class
                .getDeclaredMethod("createAgentWorkflowBinding", String.class, Map.class);
        Method updateAgentWorkflowBinding = RuntimeCompatibilityController.class
                .getDeclaredMethod("updateAgentWorkflowBinding", String.class, Long.class, Map.class);
        Method deleteAgentWorkflowBinding = RuntimeCompatibilityController.class
                .getDeclaredMethod("deleteAgentWorkflowBinding", String.class, Long.class);
        Method resolveAgentWorkflowBindingPreview = RuntimeCompatibilityController.class
                .getDeclaredMethod("resolveAgentWorkflowBindingPreview", String.class, Map.class);
        Method listRuntimes = RuntimeCompatibilityController.class.getDeclaredMethod("listRuntimes");
        Method dispatchEmbeddedRuntime = RuntimeCompatibilityController.class
                .getDeclaredMethod("dispatchEmbeddedRuntime", Map.class);
        Method executeRuntimeTool = RuntimeCompatibilityController.class
                .getDeclaredMethod("executeRuntimeTool", String.class, Map.class);
        Method executeRuntimeComposition = RuntimeCompatibilityController.class
                .getDeclaredMethod("executeRuntimeComposition", String.class, Map.class);
        Method resumeRuntimeInteraction = RuntimeCompatibilityController.class
                .getDeclaredMethod("resumeRuntimeInteraction", String.class, Map.class);
        Method createRuntimeDebugSession = RuntimeCompatibilityController.class
                .getDeclaredMethod("createRuntimeDebugSession", Map.class);
        Method getRuntimeDebugSession = RuntimeCompatibilityController.class
                .getDeclaredMethod("getRuntimeDebugSession", String.class);
        Method submitRuntimeDebugSession = RuntimeCompatibilityController.class
                .getDeclaredMethod("submitRuntimeDebugSession", String.class, Map.class);
        Method cancelRuntimeDebugSession = RuntimeCompatibilityController.class
                .getDeclaredMethod("cancelRuntimeDebugSession", String.class);
        Method listHumanApprovals = RuntimeCompatibilityController.class
                .getDeclaredMethod("listHumanApprovals", Long.class, String.class, int.class);
        Method submitHumanApproval = RuntimeCompatibilityController.class
                .getDeclaredMethod("submitHumanApproval", String.class, Map.class);
        Method cancelHumanApproval = RuntimeCompatibilityController.class
                .getDeclaredMethod("cancelHumanApproval", String.class, String.class);
        Method listGuardDecisions = RuntimeCompatibilityController.class
                .getDeclaredMethod("listGuardDecisions", String.class, String.class, String.class, String.class,
                        String.class, String.class, String.class, int.class);

        assertArrayEquals(new String[] {"/api/agent/execute", "/api/runtime/agents/execute"},
                executeAgent.getAnnotation(PostMapping.class).value());
        assertArrayEquals(new String[] {"/api/agent/execute/detailed", "/api/runtime/agents/execute/detailed"},
                executeAgentDetailed.getAnnotation(PostMapping.class).value());
        assertArrayEquals(new String[] {"/api/agent/route-evaluation", "/api/runtime/agents/route-evaluation"},
                routeEvaluation.getAnnotation(GetMapping.class).value());
        assertArrayEquals(new String[] {"/api/traces/{traceId}"}, getTrace.getAnnotation(GetMapping.class).value());
        assertArrayEquals(new String[] {"/api/traces/recent"}, listRecentTraces.getAnnotation(GetMapping.class).value());
        assertArrayEquals(new String[] {"/api/runops/traces/{traceId}"}, runOpsDetail.getAnnotation(GetMapping.class).value());
        assertArrayEquals(new String[] {"/api/runops/traces/recent"}, runOpsRecent.getAnnotation(GetMapping.class).value());
        assertArrayEquals(new String[] {"/api/runops/diagnostics"}, runOpsDiagnostics.getAnnotation(GetMapping.class).value());
        assertArrayEquals(new String[] {"/api/runops/traces/{traceId}/compare/{candidateTraceId}"},
                runOpsCompare.getAnnotation(GetMapping.class).value());
        assertArrayEquals(new String[] {"/api/runops/traces/{traceId}/replay"},
                runOpsReplay.getAnnotation(PostMapping.class).value());
        assertArrayEquals(new String[] {"/api/chat"}, chat.getAnnotation(PostMapping.class).value());
        assertArrayEquals(new String[] {"/api/v1/agents/{key}/chat", "/gateway/agents/{key}/chat"},
                gatewayAgentChat.getAnnotation(PostMapping.class).value());
        assertArrayEquals(new String[] {"/gateway/catalog"},
                gatewayCatalog.getAnnotation(GetMapping.class).value());
        assertArrayEquals(new String[] {"/api/chat/stream"}, chatStream.getAnnotation(PostMapping.class).value());
        assertArrayEquals(new String[] {"/api/chat/session/{sessionId}"},
                clearChatSession.getAnnotation(DeleteMapping.class).value());
        assertArrayEquals(new String[] {"/api/workflows"}, listWorkflows.getAnnotation(GetMapping.class).value());
        assertArrayEquals(new String[] {"/api/workflows"}, createWorkflow.getAnnotation(PostMapping.class).value());
        assertArrayEquals(new String[] {"/api/workflows/{id}"}, getWorkflow.getAnnotation(GetMapping.class).value());
        assertArrayEquals(new String[] {"/api/workflows/{id}"}, updateWorkflow.getAnnotation(PutMapping.class).value());
        assertArrayEquals(new String[] {"/api/workflows/{id}"}, deleteWorkflow.getAnnotation(DeleteMapping.class).value());
        assertArrayEquals(new String[] {"/api/workflows/graph-node-types"},
                graphNodeTypes.getAnnotation(GetMapping.class).value());
        assertArrayEquals(new String[] {"/api/workflows/runtime-validation"},
                validateWorkflowRuntime.getAnnotation(PostMapping.class).value());
        assertArrayEquals(new String[] {"/api/workflows/{id}/studio"}, workflowStudio.getAnnotation(GetMapping.class).value());
        assertArrayEquals(new String[] {"/api/workflows/{id}/studio"}, saveWorkflowStudio.getAnnotation(PutMapping.class).value());
        assertArrayEquals(new String[] {"/api/workflows/studio/debug-node"},
                debugWorkflowNode.getAnnotation(PostMapping.class).value());
        assertArrayEquals(new String[] {"/api/workflows/studio/debug-run"},
                debugWorkflowRun.getAnnotation(PostMapping.class).value());
        assertArrayEquals(new String[] {"/api/workflows/studio/generate-draft"},
                generateWorkflowStudioDraft.getAnnotation(PostMapping.class).value());
        assertArrayEquals(new String[] {"/api/workflows/studio/edit-draft"},
                editWorkflowStudioDraft.getAnnotation(PostMapping.class).value());
        assertArrayEquals(new String[] {"/api/workflows/ai-coding/workflows"},
                createWorkflowAiCodingWorkflow.getAnnotation(PostMapping.class).value());
        assertArrayEquals(new String[] {"/api/workflows/{workflowId}/ai-coding/context"},
                workflowAiCodingContext.getAnnotation(GetMapping.class).value());
        assertArrayEquals(new String[] {"/api/workflows/{workflowId}/ai-coding/validate"},
                validateWorkflowAiCoding.getAnnotation(PostMapping.class).value());
        assertArrayEquals(new String[] {"/api/workflows/{workflowId}/ai-coding/patch"},
                patchWorkflowAiCoding.getAnnotation(PostMapping.class).value());
        assertArrayEquals(new String[] {"/api/workflows/{workflowId}/ai-coding/run"},
                runWorkflowAiCoding.getAnnotation(PostMapping.class).value());
        assertArrayEquals(new String[] {"/api/workflows/{workflowId}/ai-coding/versions"},
                workflowAiCodingVersions.getAnnotation(GetMapping.class).value());
        assertArrayEquals(new String[] {"/api/workflows/{workflowId}/ai-coding/publish"},
                publishWorkflowAiCoding.getAnnotation(PostMapping.class).value());
        assertArrayEquals(new String[] {"/api/workflows/{workflowId}/ai-coding/runs"},
                workflowAiCodingRuns.getAnnotation(GetMapping.class).value());
        assertArrayEquals(new String[] {"/api/workflows/{workflowId}/ai-coding/runs/{traceId}"},
                workflowAiCodingRunDetail.getAnnotation(GetMapping.class).value());
        assertArrayEquals(new String[] {"/api/workflows/{workflowId}/ai-coding/page-assistant/catalog"},
                workflowAiCodingPageAssistantCatalog.getAnnotation(GetMapping.class).value());
        assertArrayEquals(new String[] {"/api/workflows/{workflowId}/ai-coding/page-assistant/validate"},
                validateWorkflowAiCodingPageAssistant.getAnnotation(PostMapping.class).value());
        assertArrayEquals(new String[] {"/api/workflows/{workflowId}/ai-coding/page-assistant/smoke-test"},
                smokeTestWorkflowAiCodingPageAssistant.getAnnotation(PostMapping.class).value());
        assertArrayEquals(new String[] {"/api/workflows/{workflowId}/versions"},
                listWorkflowVersions.getAnnotation(GetMapping.class).value());
        assertArrayEquals(new String[] {"/api/workflows/{workflowId}/versions"},
                publishWorkflowVersion.getAnnotation(PostMapping.class).value());
        assertArrayEquals(new String[] {"/api/workflows/{workflowId}/versions/publish"},
                publishWorkflowVersionExplicit.getAnnotation(PostMapping.class).value());
        assertArrayEquals(new String[] {"/api/workflows/{workflowId}/versions/validate"},
                validateWorkflowVersion.getAnnotation(PostMapping.class).value());
        assertArrayEquals(new String[] {"/api/workflows/{workflowId}/versions/{versionId}/rollback"},
                rollbackWorkflowVersion.getAnnotation(PostMapping.class).value());
        assertArrayEquals(new String[] {"/api/workflows/{id}/page-assistant/bind"},
                bindPageAssistantWorkflow.getAnnotation(PostMapping.class).value());
        assertArrayEquals(new String[] {"/api/agent/workflow-credentials", "/api/workflows/credentials"},
                listWorkflowCredentials.getAnnotation(GetMapping.class).value());
        assertArrayEquals(new String[] {"/api/agent/workflow-credentials", "/api/workflows/credentials"},
                createWorkflowCredential.getAnnotation(PostMapping.class).value());
        assertArrayEquals(new String[] {"/api/agent/workflow-credentials/{id}", "/api/workflows/credentials/{id}"},
                updateWorkflowCredential.getAnnotation(PutMapping.class).value());
        assertArrayEquals(new String[] {"/api/agent/workflow-credentials/{id}", "/api/workflows/credentials/{id}"},
                deleteWorkflowCredential.getAnnotation(DeleteMapping.class).value());
        assertArrayEquals(new String[] {"/api/agents"}, listAgents.getAnnotation(GetMapping.class).value());
        assertArrayEquals(new String[] {"/api/agents"}, createAgent.getAnnotation(PostMapping.class).value());
        assertArrayEquals(new String[] {"/api/agents/{id}"}, getAgent.getAnnotation(GetMapping.class).value());
        assertArrayEquals(new String[] {"/api/agents/{id}"}, updateAgent.getAnnotation(PutMapping.class).value());
        assertArrayEquals(new String[] {"/api/agents/{id}"}, deleteAgent.getAnnotation(DeleteMapping.class).value());
        assertArrayEquals(new String[] {"/api/agents/{agentId}/workflow-bindings"},
                listAgentWorkflowBindings.getAnnotation(GetMapping.class).value());
        assertArrayEquals(new String[] {"/api/agents/{agentId}/workflow-bindings"},
                createAgentWorkflowBinding.getAnnotation(PostMapping.class).value());
        assertArrayEquals(new String[] {"/api/agents/{agentId}/workflow-bindings/{bindingId}"},
                updateAgentWorkflowBinding.getAnnotation(PutMapping.class).value());
        assertArrayEquals(new String[] {"/api/agents/{agentId}/workflow-bindings/{bindingId}"},
                deleteAgentWorkflowBinding.getAnnotation(DeleteMapping.class).value());
        assertArrayEquals(new String[] {"/api/agents/{agentId}/workflow-bindings/resolve-preview"},
                resolveAgentWorkflowBindingPreview.getAnnotation(PostMapping.class).value());
        assertArrayEquals(new String[] {"/api/runtimes"}, listRuntimes.getAnnotation(GetMapping.class).value());
        assertArrayEquals(new String[] {"/api/runtimes/embedded/dispatch"},
                dispatchEmbeddedRuntime.getAnnotation(PostMapping.class).value());
        assertArrayEquals(new String[] {"/api/runtime/tools/{qualifiedName}/execute"},
                executeRuntimeTool.getAnnotation(PostMapping.class).value());
        assertArrayEquals(new String[] {"/api/runtime/compositions/{qualifiedName}/execute"},
                executeRuntimeComposition.getAnnotation(PostMapping.class).value());
        assertArrayEquals(new String[] {"/api/runtime/interactions/{sessionId}/resume"},
                resumeRuntimeInteraction.getAnnotation(PostMapping.class).value());
        assertArrayEquals(new String[] {"/api/runtime/debug-sessions"},
                createRuntimeDebugSession.getAnnotation(PostMapping.class).value());
        assertArrayEquals(new String[] {"/api/runtime/debug-sessions/{sessionId}"},
                getRuntimeDebugSession.getAnnotation(GetMapping.class).value());
        assertArrayEquals(new String[] {"/api/runtime/debug-sessions/{sessionId}/submit"},
                submitRuntimeDebugSession.getAnnotation(PostMapping.class).value());
        assertArrayEquals(new String[] {"/api/runtime/debug-sessions/{sessionId}/cancel"},
                cancelRuntimeDebugSession.getAnnotation(PostMapping.class).value());
        assertArrayEquals(new String[] {"/api/agent/interactions/human-approvals",
                        "/api/runtime/interactions/human-approvals"},
                listHumanApprovals.getAnnotation(GetMapping.class).value());
        assertArrayEquals(new String[] {"/api/agent/interactions/human-approvals/{interactionId}/submit",
                        "/api/runtime/interactions/human-approvals/{interactionId}/submit"},
                submitHumanApproval.getAnnotation(PostMapping.class).value());
        assertArrayEquals(new String[] {"/api/agent/interactions/human-approvals/{interactionId}",
                        "/api/runtime/interactions/human-approvals/{interactionId}"},
                cancelHumanApproval.getAnnotation(DeleteMapping.class).value());
        assertArrayEquals(new String[] {"/api/trace-center/guard-decisions"},
                listGuardDecisions.getAnnotation(GetMapping.class).value());
    }

    @Test
    void delegatesTraceCenterGuardDecisionsToRuntimeService() {
        RuntimeProxyClient runtimeProxyClient = mock(RuntimeProxyClient.class);
        RuntimeCompatibilityController controller = new RuntimeCompatibilityController(runtimeProxyClient);
        ResponseEntity<Object> expected = ResponseEntity.ok(java.util.List.of(Map.of(
                "traceId", "trace-1",
                "targetName", "orders.search",
                "decision", "DENY")));
        when(runtimeProxyClient.listGuardDecisions("trace-1", "TOOL_ACL", "TOOL", "orders.search",
                "DENY", null, null, 50)).thenReturn(expected);

        ResponseEntity<Object> response = controller.listGuardDecisions(
                "trace-1",
                "TOOL_ACL",
                "TOOL",
                "orders.search",
                "DENY",
                null,
                null,
                50);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(expected.getBody(), response.getBody());
        verify(runtimeProxyClient).listGuardDecisions("trace-1", "TOOL_ACL", "TOOL", "orders.search",
                "DENY", null, null, 50);
    }

    @Test
    void delegatesAgentExecutionToRuntimeService() {
        RuntimeProxyClient runtimeProxyClient = mock(RuntimeProxyClient.class);
        RuntimeCompatibilityController controller = new RuntimeCompatibilityController(runtimeProxyClient);
        Map<String, Object> request = Map.of("agentId", "agent-1", "input", "hello");
        ResponseEntity<Map<String, Object>> delegated = ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(Map.of("traceId", "trace-1", "status", "RUNNING"));
        when(runtimeProxyClient.executeAgent(request)).thenReturn(delegated);

        ResponseEntity<Map<String, Object>> response = controller.executeAgent(request);

        assertEquals(delegated, response);
        verify(runtimeProxyClient).executeAgent(request);
    }

    @Test
    void delegatesDetailedAgentExecutionToRuntimeService() {
        RuntimeProxyClient runtimeProxyClient = mock(RuntimeProxyClient.class);
        RuntimeCompatibilityController controller = new RuntimeCompatibilityController(runtimeProxyClient);
        Map<String, Object> request = Map.of("agentDefinitionId", "agent-1", "message", "hello");
        ResponseEntity<Map<String, Object>> delegated = ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED)
                .body(Map.of("code", "RUNTIME_AGENT_EXECUTION_PENDING"));
        when(runtimeProxyClient.executeAgentDetailed(request)).thenReturn(delegated);

        ResponseEntity<Map<String, Object>> response = controller.executeAgentDetailed(request);

        assertEquals(delegated, response);
        verify(runtimeProxyClient).executeAgentDetailed(request);
    }

    @Test
    void delegatesTraceLookupToRuntimeService() {
        RuntimeProxyClient runtimeProxyClient = mock(RuntimeProxyClient.class);
        RuntimeCompatibilityController controller = new RuntimeCompatibilityController(runtimeProxyClient);
        ResponseEntity<Map<String, Object>> delegated = ResponseEntity.ok(Map.of(
                "traceId", "trace-1",
                "status", "SUCCESS"
        ));
        when(runtimeProxyClient.getTrace("trace-1")).thenReturn(delegated);

        ResponseEntity<Map<String, Object>> response = controller.getTrace("trace-1");

        assertEquals(delegated, response);
        verify(runtimeProxyClient).getTrace("trace-1");
    }

    @Test
    void delegatesRecentTraceListToRuntimeService() {
        RuntimeProxyClient runtimeProxyClient = mock(RuntimeProxyClient.class);
        RuntimeCompatibilityController controller = new RuntimeCompatibilityController(runtimeProxyClient);
        ResponseEntity<Map<String, Object>> delegated = ResponseEntity.ok(Map.of(
                "items", java.util.List.of(Map.of("traceId", "trace-1"))
        ));
        when(runtimeProxyClient.listRecentTraces("user-1", 7, 10)).thenReturn(delegated);

        ResponseEntity<Map<String, Object>> response = controller.listRecentTraces("user-1", 7, 10);

        assertEquals(delegated, response);
        verify(runtimeProxyClient).listRecentTraces("user-1", 7, 10);
    }

    @Test
    void delegatesRouteEvaluationToRuntimeService() {
        RuntimeProxyClient runtimeProxyClient = mock(RuntimeProxyClient.class);
        RuntimeCompatibilityController controller = new RuntimeCompatibilityController(runtimeProxyClient);
        ResponseEntity<Map<String, Object>> delegated = ResponseEntity.ok(Map.of(
                "days", 30,
                "traceCount", 7
        ));
        when(runtimeProxyClient.routeEvaluation(30)).thenReturn(delegated);

        ResponseEntity<Map<String, Object>> response = controller.routeEvaluation(30);

        assertEquals(delegated, response);
        verify(runtimeProxyClient).routeEvaluation(30);
    }

    @Test
    void delegatesRunOpsDetailToRuntimeService() {
        RuntimeProxyClient runtimeProxyClient = mock(RuntimeProxyClient.class);
        RuntimeCompatibilityController controller = new RuntimeCompatibilityController(runtimeProxyClient);
        ResponseEntity<Map<String, Object>> delegated = ResponseEntity.ok(Map.of(
                "summary", Map.of("traceId", "trace-1", "status", "SUCCESS")
        ));
        when(runtimeProxyClient.runOpsDetail("trace-1")).thenReturn(delegated);

        ResponseEntity<Map<String, Object>> response = controller.runOpsDetail("trace-1");

        assertEquals(delegated, response);
        verify(runtimeProxyClient).runOpsDetail("trace-1");
    }

    @Test
    void delegatesRunOpsRecentToRuntimeService() {
        RuntimeProxyClient runtimeProxyClient = mock(RuntimeProxyClient.class);
        RuntimeCompatibilityController controller = new RuntimeCompatibilityController(runtimeProxyClient);
        ResponseEntity<Map<String, Object>> delegated = ResponseEntity.ok(Map.of(
                "items", java.util.List.of(Map.of("traceId", "trace-1"))
        ));
        when(runtimeProxyClient.runOpsRecent("user-1", 25, 14)).thenReturn(delegated);

        ResponseEntity<Map<String, Object>> response = controller.runOpsRecent("user-1", 25, 14);

        assertEquals(delegated, response);
        verify(runtimeProxyClient).runOpsRecent("user-1", 25, 14);
    }

    @Test
    void delegatesRunOpsCompareToRuntimeService() {
        RuntimeProxyClient runtimeProxyClient = mock(RuntimeProxyClient.class);
        RuntimeCompatibilityController controller = new RuntimeCompatibilityController(runtimeProxyClient);
        ResponseEntity<Map<String, Object>> delegated = ResponseEntity.ok(Map.of(
                "baseline", Map.of("traceId", "baseline"),
                "candidate", Map.of("traceId", "candidate")
        ));
        when(runtimeProxyClient.runOpsCompare("baseline", "candidate")).thenReturn(delegated);

        ResponseEntity<Map<String, Object>> response = controller.runOpsCompare("baseline", "candidate");

        assertEquals(delegated, response);
        verify(runtimeProxyClient).runOpsCompare("baseline", "candidate");
    }

    @Test
    void delegatesRunOpsDiagnosticsToRuntimeService() {
        RuntimeProxyClient runtimeProxyClient = mock(RuntimeProxyClient.class);
        RuntimeCompatibilityController controller = new RuntimeCompatibilityController(runtimeProxyClient);
        ResponseEntity<Map<String, Object>> delegated = ResponseEntity.ok(Map.of(
                "failureClusters", java.util.List.of(),
                "versionComparisons", java.util.List.of()
        ));
        when(runtimeProxyClient.runOpsDiagnostics("user-1", 25, 14)).thenReturn(delegated);

        ResponseEntity<Map<String, Object>> response = controller.runOpsDiagnostics("user-1", 25, 14);

        assertEquals(delegated, response);
        verify(runtimeProxyClient).runOpsDiagnostics("user-1", 25, 14);
    }

    @Test
    void delegatesRunOpsReplayToRuntimeService() {
        RuntimeProxyClient runtimeProxyClient = mock(RuntimeProxyClient.class);
        RuntimeCompatibilityController controller = new RuntimeCompatibilityController(runtimeProxyClient);
        Map<String, Object> request = Map.of("useSnapshot", true);
        ResponseEntity<Map<String, Object>> delegated = ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED)
                .body(Map.of("code", "RUNTIME_RUNOPS_REPLAY_PENDING"));
        when(runtimeProxyClient.runOpsReplay("trace-1", request)).thenReturn(delegated);

        ResponseEntity<Map<String, Object>> response = controller.runOpsReplay("trace-1", request);

        assertEquals(delegated, response);
        verify(runtimeProxyClient).runOpsReplay("trace-1", request);
    }

    @Test
    void delegatesLightweightChatToRuntimeService() {
        RuntimeProxyClient runtimeProxyClient = mock(RuntimeProxyClient.class);
        RuntimeCompatibilityController controller = new RuntimeCompatibilityController(runtimeProxyClient);
        Map<String, Object> request = Map.of("message", "hello");
        ResponseEntity<Object> chat = ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED)
                .body(Map.of("code", "RUNTIME_CHAT_PENDING"));
        ResponseEntity<String> stream = ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED)
                .body("event: error\ndata: RUNTIME_CHAT_PENDING\n\n");
        ResponseEntity<Object> cleared = ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED)
                .body(Map.of("code", "RUNTIME_CHAT_PENDING"));
        when(runtimeProxyClient.chat(request)).thenReturn(chat);
        when(runtimeProxyClient.chatStream(request)).thenReturn(stream);
        when(runtimeProxyClient.clearChatSession("s1")).thenReturn(cleared);

        assertEquals(chat, controller.chat(request));
        assertEquals(stream, controller.chatStream(request));
        assertEquals(cleared, controller.clearChatSession("s1"));
        verify(runtimeProxyClient).chat(request);
        verify(runtimeProxyClient).chatStream(request);
        verify(runtimeProxyClient).clearChatSession("s1");
    }

    @Test
    void delegatesGatewayAgentChatToRuntimeChatWithoutRetiredPlatformProxy() {
        RuntimeProxyClient runtimeProxyClient = mock(RuntimeProxyClient.class);
        RuntimeCompatibilityController controller = new RuntimeCompatibilityController(runtimeProxyClient);
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("message", "hello");
        request.put("sessionId", "s1");
        request.put("userId", "u1");
        Map<String, Object> runtimeRequest = new LinkedHashMap<>(request);
        runtimeRequest.put("agentDefinitionId", "orders-bot");
        runtimeRequest.put("intentHint", "AGENT_GATEWAY_CHAT");
        ResponseEntity<Object> delegated = ResponseEntity.ok(Map.of(
                "success", false,
                "answer", "hi",
                "sessionId", "s1"));
        when(runtimeProxyClient.executeAgent(runtimeRequest)).thenReturn(ResponseEntity.ok(Map.of(
                "success", false,
                "answer", "hi",
                "sessionId", "s1")));

        ResponseEntity<Object> response = controller.gatewayAgentChat("orders-bot", request);

        assertEquals(delegated.getBody(), response.getBody());
        assertFalse(request.containsKey("agentDefinitionId"));
        assertFalse(request.containsKey("intentHint"));
        verify(runtimeProxyClient).executeAgent(runtimeRequest);
    }

    @Test
    @SuppressWarnings("unchecked")
    void delegatesGatewayCatalogToRuntimeAgentCatalogWithoutRetiredPlatformProxy() {
        RuntimeProxyClient runtimeProxyClient = mock(RuntimeProxyClient.class);
        RuntimeCompatibilityController controller = new RuntimeCompatibilityController(runtimeProxyClient);
        Map<String, Object> visible = new LinkedHashMap<>();
        visible.put("id", "agent-1");
        visible.put("keySlug", "orders-bot");
        visible.put("name", "Orders Bot");
        visible.put("projectCode", "orders");
        visible.put("visibility", "PUBLIC");
        visible.put("enabled", true);
        Map<String, Object> disabled = new LinkedHashMap<>(visible);
        disabled.put("id", "agent-2");
        disabled.put("enabled", false);
        Map<String, Object> privateAgent = new LinkedHashMap<>(visible);
        privateAgent.put("id", "agent-3");
        privateAgent.put("visibility", "PRIVATE");
        when(runtimeProxyClient.listAgents(7L, null, null))
                .thenReturn(ResponseEntity.ok(java.util.List.of(visible, disabled, privateAgent)));

        ResponseEntity<Object> response = controller.gatewayCatalog(7L);

        Map<String, Object> body = (Map<String, Object>) response.getBody();
        java.util.List<Map<String, Object>> agents = (java.util.List<Map<String, Object>>) body.get("agents");
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(1, agents.size());
        assertEquals("agent-1", agents.get(0).get("id"));
        assertEquals("orders-bot", agents.get(0).get("keySlug"));
        assertEquals("Orders Bot", agents.get(0).get("name"));
        assertEquals("orders", agents.get(0).get("projectCode"));
        assertEquals("PUBLIC", agents.get(0).get("visibility"));
        assertEquals(java.util.List.of(), body.get("capabilities"));
        verify(runtimeProxyClient).listAgents(7L, null, null);
    }

    @Test
    void delegatesWorkflowCrudToRuntimeService() {
        RuntimeProxyClient runtimeProxyClient = mock(RuntimeProxyClient.class);
        RuntimeCompatibilityController controller = new RuntimeCompatibilityController(runtimeProxyClient);
        Map<String, Object> request = Map.of("keySlug", "orders", "name", "Orders");
        ResponseEntity<Object> list = ResponseEntity.ok(java.util.List.of(request));
        ResponseEntity<Object> created = ResponseEntity.status(HttpStatus.CREATED).body(request);
        ResponseEntity<Object> updated = ResponseEntity.ok(request);
        ResponseEntity<Object> found = ResponseEntity.ok(request);
        ResponseEntity<Object> deleted = ResponseEntity.noContent().build();
        when(runtimeProxyClient.listWorkflows(7L, "orders", "CHAT", "DRAFT")).thenReturn(list);
        when(runtimeProxyClient.createWorkflow(request)).thenReturn(created);
        when(runtimeProxyClient.getWorkflow("wf-1")).thenReturn(found);
        when(runtimeProxyClient.updateWorkflow("wf-1", request)).thenReturn(updated);
        when(runtimeProxyClient.deleteWorkflow("wf-1")).thenReturn(deleted);

        assertEquals(list, controller.listWorkflows(7L, "orders", "CHAT", "DRAFT"));
        assertEquals(created, controller.createWorkflow(request));
        assertEquals(found, controller.getWorkflow("wf-1"));
        assertEquals(updated, controller.updateWorkflow("wf-1", request));
        assertEquals(deleted, controller.deleteWorkflow("wf-1"));
        verify(runtimeProxyClient).listWorkflows(7L, "orders", "CHAT", "DRAFT");
        verify(runtimeProxyClient).createWorkflow(request);
        verify(runtimeProxyClient).getWorkflow("wf-1");
        verify(runtimeProxyClient).updateWorkflow("wf-1", request);
        verify(runtimeProxyClient).deleteWorkflow("wf-1");
    }

    @Test
    void delegatesWorkflowGraphNodeTypesToRuntimeService() {
        RuntimeProxyClient runtimeProxyClient = mock(RuntimeProxyClient.class);
        RuntimeCompatibilityController controller = new RuntimeCompatibilityController(runtimeProxyClient);
        ResponseEntity<Object> delegated = ResponseEntity.ok(java.util.List.of(Map.of("type", "LLM")));
        when(runtimeProxyClient.graphNodeTypes()).thenReturn(delegated);

        ResponseEntity<Object> response = controller.graphNodeTypes();

        assertEquals(delegated, response);
        verify(runtimeProxyClient).graphNodeTypes();
    }

    @Test
    void delegatesWorkflowRuntimeValidationToRuntimeService() {
        RuntimeProxyClient runtimeProxyClient = mock(RuntimeProxyClient.class);
        RuntimeCompatibilityController controller = new RuntimeCompatibilityController(runtimeProxyClient);
        Map<String, Object> request = Map.of("workflowId", "wf-1");
        ResponseEntity<Object> delegated = ResponseEntity.ok(Map.of("valid", true));
        when(runtimeProxyClient.validateWorkflowRuntime(request)).thenReturn(delegated);

        ResponseEntity<Object> response = controller.validateWorkflowRuntime(request);

        assertEquals(delegated, response);
        verify(runtimeProxyClient).validateWorkflowRuntime(request);
    }

    @Test
    void delegatesWorkflowStudioStateAndSaveToRuntimeService() {
        RuntimeProxyClient runtimeProxyClient = mock(RuntimeProxyClient.class);
        RuntimeCompatibilityController controller = new RuntimeCompatibilityController(runtimeProxyClient);
        Map<String, Object> request = Map.of("graphSpecJson", "{}");
        ResponseEntity<Object> state = ResponseEntity.ok(Map.of("workflowId", "wf-1"));
        ResponseEntity<Object> saved = ResponseEntity.ok(Map.of("id", "wf-1"));
        when(runtimeProxyClient.workflowStudio("wf-1")).thenReturn(state);
        when(runtimeProxyClient.saveWorkflowStudio("wf-1", request)).thenReturn(saved);

        assertEquals(state, controller.workflowStudio("wf-1"));
        assertEquals(saved, controller.saveWorkflowStudio("wf-1", request));
        verify(runtimeProxyClient).workflowStudio("wf-1");
        verify(runtimeProxyClient).saveWorkflowStudio("wf-1", request);
    }

    @Test
    void delegatesWorkflowStudioDebugToRuntimeService() {
        RuntimeProxyClient runtimeProxyClient = mock(RuntimeProxyClient.class);
        RuntimeCompatibilityController controller = new RuntimeCompatibilityController(runtimeProxyClient);
        Map<String, Object> request = Map.of("workflowId", "wf-1", "nodeId", "n1");
        ResponseEntity<Object> node = ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED)
                .body(Map.of("code", "RUNTIME_WORKFLOW_STUDIO_DEBUG_PENDING"));
        ResponseEntity<Object> run = ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED)
                .body(Map.of("code", "RUNTIME_WORKFLOW_STUDIO_DEBUG_PENDING"));
        when(runtimeProxyClient.debugWorkflowNode(request)).thenReturn(node);
        when(runtimeProxyClient.debugWorkflowRun(request)).thenReturn(run);

        assertEquals(node, controller.debugWorkflowNode(request));
        assertEquals(run, controller.debugWorkflowRun(request));
        verify(runtimeProxyClient).debugWorkflowNode(request);
        verify(runtimeProxyClient).debugWorkflowRun(request);
    }

    @Test
    void delegatesWorkflowStudioDraftToRuntimeService() {
        RuntimeProxyClient runtimeProxyClient = mock(RuntimeProxyClient.class);
        RuntimeCompatibilityController controller = new RuntimeCompatibilityController(runtimeProxyClient);
        Map<String, Object> request = Map.of("prompt", "draft an order workflow");
        ResponseEntity<Object> generated = ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED)
                .body(Map.of("code", "RUNTIME_WORKFLOW_STUDIO_DRAFT_PENDING"));
        ResponseEntity<Object> edited = ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED)
                .body(Map.of("code", "RUNTIME_WORKFLOW_STUDIO_DRAFT_PENDING"));
        when(runtimeProxyClient.generateWorkflowStudioDraft(request)).thenReturn(generated);
        when(runtimeProxyClient.editWorkflowStudioDraft(request)).thenReturn(edited);

        assertEquals(generated, controller.generateWorkflowStudioDraft(request));
        assertEquals(edited, controller.editWorkflowStudioDraft(request));
        verify(runtimeProxyClient).generateWorkflowStudioDraft(request);
        verify(runtimeProxyClient).editWorkflowStudioDraft(request);
    }

    @Test
    void delegatesWorkflowAiCodingToRuntimeService() {
        RuntimeProxyClient runtimeProxyClient = mock(RuntimeProxyClient.class);
        RuntimeCompatibilityController controller = new RuntimeCompatibilityController(runtimeProxyClient);
        Map<String, Object> request = Map.of("instruction", "add guard");
        ResponseEntity<Object> pending = ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED)
                .body(Map.of("code", "RUNTIME_WORKFLOW_AI_CODING_PENDING"));
        when(runtimeProxyClient.createWorkflowAiCodingWorkflow(request)).thenReturn(pending);
        when(runtimeProxyClient.workflowAiCodingContext("wf-1")).thenReturn(pending);
        when(runtimeProxyClient.validateWorkflowAiCoding("wf-1", request)).thenReturn(pending);
        when(runtimeProxyClient.patchWorkflowAiCoding("wf-1", request)).thenReturn(pending);
        when(runtimeProxyClient.runWorkflowAiCoding("wf-1", request)).thenReturn(pending);
        when(runtimeProxyClient.workflowAiCodingVersions("wf-1")).thenReturn(pending);
        when(runtimeProxyClient.publishWorkflowAiCoding("wf-1", request)).thenReturn(pending);
        when(runtimeProxyClient.workflowAiCodingRuns("wf-1", 20, 7)).thenReturn(pending);
        when(runtimeProxyClient.workflowAiCodingRunDetail("wf-1", "trace-1")).thenReturn(pending);
        when(runtimeProxyClient.workflowAiCodingPageAssistantCatalog("wf-1")).thenReturn(pending);
        when(runtimeProxyClient.validateWorkflowAiCodingPageAssistant("wf-1", request)).thenReturn(pending);
        when(runtimeProxyClient.smokeTestWorkflowAiCodingPageAssistant("wf-1", request)).thenReturn(pending);

        assertEquals(pending, controller.createWorkflowAiCodingWorkflow(request));
        assertEquals(pending, controller.workflowAiCodingContext("wf-1"));
        assertEquals(pending, controller.validateWorkflowAiCoding("wf-1", request));
        assertEquals(pending, controller.patchWorkflowAiCoding("wf-1", request));
        assertEquals(pending, controller.runWorkflowAiCoding("wf-1", request));
        assertEquals(pending, controller.workflowAiCodingVersions("wf-1"));
        assertEquals(pending, controller.publishWorkflowAiCoding("wf-1", request));
        assertEquals(pending, controller.workflowAiCodingRuns("wf-1", 20, 7));
        assertEquals(pending, controller.workflowAiCodingRunDetail("wf-1", "trace-1"));
        assertEquals(pending, controller.workflowAiCodingPageAssistantCatalog("wf-1"));
        assertEquals(pending, controller.validateWorkflowAiCodingPageAssistant("wf-1", request));
        assertEquals(pending, controller.smokeTestWorkflowAiCodingPageAssistant("wf-1", request));
        verify(runtimeProxyClient).createWorkflowAiCodingWorkflow(request);
        verify(runtimeProxyClient).workflowAiCodingContext("wf-1");
        verify(runtimeProxyClient).validateWorkflowAiCoding("wf-1", request);
        verify(runtimeProxyClient).patchWorkflowAiCoding("wf-1", request);
        verify(runtimeProxyClient).runWorkflowAiCoding("wf-1", request);
        verify(runtimeProxyClient).workflowAiCodingVersions("wf-1");
        verify(runtimeProxyClient).publishWorkflowAiCoding("wf-1", request);
        verify(runtimeProxyClient).workflowAiCodingRuns("wf-1", 20, 7);
        verify(runtimeProxyClient).workflowAiCodingRunDetail("wf-1", "trace-1");
        verify(runtimeProxyClient).workflowAiCodingPageAssistantCatalog("wf-1");
        verify(runtimeProxyClient).validateWorkflowAiCodingPageAssistant("wf-1", request);
        verify(runtimeProxyClient).smokeTestWorkflowAiCodingPageAssistant("wf-1", request);
    }

    @Test
    void delegatesWorkflowVersionOperationsToRuntimeService() {
        RuntimeProxyClient runtimeProxyClient = mock(RuntimeProxyClient.class);
        RuntimeCompatibilityController controller = new RuntimeCompatibilityController(runtimeProxyClient);
        Map<String, Object> request = Map.of("version", "v1.0.0");
        Map<String, Object> rollbackRequest = Map.of("operator", "bob");
        ResponseEntity<Object> versions = ResponseEntity.ok(java.util.List.of(Map.of("version", "v1.0.0")));
        ResponseEntity<Object> published = ResponseEntity.ok(Map.of("id", 1L));
        ResponseEntity<Object> validation = ResponseEntity.ok(Map.of("valid", true));
        ResponseEntity<Object> rolled = ResponseEntity.ok(Map.of("id", 1L));
        when(runtimeProxyClient.listWorkflowVersions("wf-1")).thenReturn(versions);
        when(runtimeProxyClient.publishWorkflowVersion("wf-1", request)).thenReturn(published);
        when(runtimeProxyClient.publishWorkflowVersionExplicit("wf-1", request)).thenReturn(published);
        when(runtimeProxyClient.validateWorkflowVersion("wf-1")).thenReturn(validation);
        when(runtimeProxyClient.rollbackWorkflowVersion("wf-1", 1L, rollbackRequest)).thenReturn(rolled);

        assertEquals(versions, controller.listWorkflowVersions("wf-1"));
        assertEquals(published, controller.publishWorkflowVersion("wf-1", request));
        assertEquals(published, controller.publishWorkflowVersionExplicit("wf-1", request));
        assertEquals(validation, controller.validateWorkflowVersion("wf-1"));
        assertEquals(rolled, controller.rollbackWorkflowVersion("wf-1", 1L, rollbackRequest));
        verify(runtimeProxyClient).listWorkflowVersions("wf-1");
        verify(runtimeProxyClient).publishWorkflowVersion("wf-1", request);
        verify(runtimeProxyClient).publishWorkflowVersionExplicit("wf-1", request);
        verify(runtimeProxyClient).validateWorkflowVersion("wf-1");
        verify(runtimeProxyClient).rollbackWorkflowVersion("wf-1", 1L, rollbackRequest);
    }

    @Test
    void delegatesPageAssistantWorkflowBindToRuntimeService() {
        RuntimeProxyClient runtimeProxyClient = mock(RuntimeProxyClient.class);
        RuntimeCompatibilityController controller = new RuntimeCompatibilityController(runtimeProxyClient);
        Map<String, Object> request = Map.of(
                "projectCode", "orders",
                "agentId", "agent-1",
                "pageKey", "orders.list");
        ResponseEntity<Object> delegated = ResponseEntity.ok(Map.of(
                "agentId", "agent-1",
                "workflowId", "wf-1",
                "bindingId", 11L));
        when(runtimeProxyClient.bindPageAssistantWorkflow("wf-1", request)).thenReturn(delegated);

        ResponseEntity<Object> response = controller.bindPageAssistantWorkflow("wf-1", request);

        assertEquals(delegated, response);
        verify(runtimeProxyClient).bindPageAssistantWorkflow("wf-1", request);
    }

    @Test
    void delegatesWorkflowCredentialOperationsToRuntimeService() {
        RuntimeProxyClient runtimeProxyClient = mock(RuntimeProxyClient.class);
        RuntimeCompatibilityController controller = new RuntimeCompatibilityController(runtimeProxyClient);
        Map<String, Object> request = Map.of("credentialRef", "cred_orders", "name", "Orders API");
        ResponseEntity<Object> list = ResponseEntity.ok(java.util.List.of(request));
        ResponseEntity<Object> created = ResponseEntity.ok(request);
        ResponseEntity<Object> updated = ResponseEntity.ok(request);
        ResponseEntity<Object> deleted = ResponseEntity.noContent().build();
        when(runtimeProxyClient.listWorkflowCredentials(7L, "orders")).thenReturn(list);
        when(runtimeProxyClient.createWorkflowCredential(request)).thenReturn(created);
        when(runtimeProxyClient.updateWorkflowCredential(1L, request)).thenReturn(updated);
        when(runtimeProxyClient.deleteWorkflowCredential(1L)).thenReturn(deleted);

        assertEquals(list, controller.listWorkflowCredentials(7L, "orders"));
        assertEquals(created, controller.createWorkflowCredential(request));
        assertEquals(updated, controller.updateWorkflowCredential(1L, request));
        assertEquals(deleted, controller.deleteWorkflowCredential(1L));
        verify(runtimeProxyClient).listWorkflowCredentials(7L, "orders");
        verify(runtimeProxyClient).createWorkflowCredential(request);
        verify(runtimeProxyClient).updateWorkflowCredential(1L, request);
        verify(runtimeProxyClient).deleteWorkflowCredential(1L);
    }

    @Test
    void delegatesAgentEntryCrudToRuntimeService() {
        RuntimeProxyClient runtimeProxyClient = mock(RuntimeProxyClient.class);
        RuntimeCompatibilityController controller = new RuntimeCompatibilityController(runtimeProxyClient);
        Map<String, Object> request = Map.of("id", "agent-1", "keySlug", "orders-agent", "name", "Orders Agent");
        ResponseEntity<Object> list = ResponseEntity.ok(java.util.List.of(request));
        ResponseEntity<Object> created = ResponseEntity.ok(request);
        ResponseEntity<Object> found = ResponseEntity.ok(request);
        ResponseEntity<Object> updated = ResponseEntity.ok(request);
        ResponseEntity<Object> deleted = ResponseEntity.noContent().build();
        when(runtimeProxyClient.listAgents(7L, "orders", "PAGE_COPILOT")).thenReturn(list);
        when(runtimeProxyClient.createAgent(request)).thenReturn(created);
        when(runtimeProxyClient.getAgent("agent-1")).thenReturn(found);
        when(runtimeProxyClient.updateAgent("agent-1", request)).thenReturn(updated);
        when(runtimeProxyClient.deleteAgent("agent-1")).thenReturn(deleted);

        assertEquals(list, controller.listAgents(7L, "orders", "PAGE_COPILOT"));
        assertEquals(created, controller.createAgent(request));
        assertEquals(found, controller.getAgent("agent-1"));
        assertEquals(updated, controller.updateAgent("agent-1", request));
        assertEquals(deleted, controller.deleteAgent("agent-1"));
        verify(runtimeProxyClient).listAgents(7L, "orders", "PAGE_COPILOT");
        verify(runtimeProxyClient).createAgent(request);
        verify(runtimeProxyClient).getAgent("agent-1");
        verify(runtimeProxyClient).updateAgent("agent-1", request);
        verify(runtimeProxyClient).deleteAgent("agent-1");
    }

    @Test
    void delegatesAgentWorkflowBindingOperationsToRuntimeService() {
        RuntimeProxyClient runtimeProxyClient = mock(RuntimeProxyClient.class);
        RuntimeCompatibilityController controller = new RuntimeCompatibilityController(runtimeProxyClient);
        Map<String, Object> request = Map.of("workflowId", "wf-1", "bindingType", "PAGE");
        ResponseEntity<Object> list = ResponseEntity.ok(java.util.List.of(request));
        ResponseEntity<Object> created = ResponseEntity.ok(request);
        ResponseEntity<Object> updated = ResponseEntity.ok(request);
        ResponseEntity<Object> deleted = ResponseEntity.noContent().build();
        ResponseEntity<Object> resolved = ResponseEntity.ok(request);
        when(runtimeProxyClient.listAgentWorkflowBindings("agent-1")).thenReturn(list);
        when(runtimeProxyClient.createAgentWorkflowBinding("agent-1", request)).thenReturn(created);
        when(runtimeProxyClient.updateAgentWorkflowBinding("agent-1", 9L, request)).thenReturn(updated);
        when(runtimeProxyClient.deleteAgentWorkflowBinding("agent-1", 9L)).thenReturn(deleted);
        when(runtimeProxyClient.resolveAgentWorkflowBindingPreview("agent-1", request)).thenReturn(resolved);

        assertEquals(list, controller.listAgentWorkflowBindings("agent-1"));
        assertEquals(created, controller.createAgentWorkflowBinding("agent-1", request));
        assertEquals(updated, controller.updateAgentWorkflowBinding("agent-1", 9L, request));
        assertEquals(deleted, controller.deleteAgentWorkflowBinding("agent-1", 9L));
        assertEquals(resolved, controller.resolveAgentWorkflowBindingPreview("agent-1", request));
        verify(runtimeProxyClient).listAgentWorkflowBindings("agent-1");
        verify(runtimeProxyClient).createAgentWorkflowBinding("agent-1", request);
        verify(runtimeProxyClient).updateAgentWorkflowBinding("agent-1", 9L, request);
        verify(runtimeProxyClient).deleteAgentWorkflowBinding("agent-1", 9L);
        verify(runtimeProxyClient).resolveAgentWorkflowBindingPreview("agent-1", request);
    }

    @Test
    void delegatesRuntimeRegistryOperationsToRuntimeService() {
        RuntimeProxyClient runtimeProxyClient = mock(RuntimeProxyClient.class);
        RuntimeCompatibilityController controller = new RuntimeCompatibilityController(runtimeProxyClient);
        Map<String, Object> request = Map.of("projectCode", "orders", "instanceId", "host-1");
        ResponseEntity<Object> runtimes = ResponseEntity.ok(java.util.List.of(Map.of("id", "platform:LANGGRAPH4J")));
        ResponseEntity<Object> dispatched = ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED)
                .body(Map.of("code", "RUNTIME_EMBEDDED_DISPATCH_PENDING"));
        when(runtimeProxyClient.listRuntimes()).thenReturn(runtimes);
        when(runtimeProxyClient.dispatchEmbeddedRuntime(request)).thenReturn(dispatched);

        assertEquals(runtimes, controller.listRuntimes());
        assertEquals(dispatched, controller.dispatchEmbeddedRuntime(request));
        verify(runtimeProxyClient).listRuntimes();
        verify(runtimeProxyClient).dispatchEmbeddedRuntime(request);
    }

    @Test
    void delegatesRuntimeCapabilityExecutionToRuntimeService() {
        RuntimeProxyClient runtimeProxyClient = mock(RuntimeProxyClient.class);
        RuntimeCompatibilityController controller = new RuntimeCompatibilityController(runtimeProxyClient);
        Map<String, Object> request = Map.of("params", Map.of("x", 1));
        ResponseEntity<Object> pending = ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED)
                .body(Map.of("code", "RUNTIME_CAPABILITY_EXECUTION_PENDING"));
        when(runtimeProxyClient.executeRuntimeTool("system.echo", request)).thenReturn(pending);
        when(runtimeProxyClient.executeRuntimeComposition("system.flow", request)).thenReturn(pending);
        when(runtimeProxyClient.resumeRuntimeInteraction("session-1", request)).thenReturn(pending);

        assertEquals(pending, controller.executeRuntimeTool("system.echo", request));
        assertEquals(pending, controller.executeRuntimeComposition("system.flow", request));
        assertEquals(pending, controller.resumeRuntimeInteraction("session-1", request));
        verify(runtimeProxyClient).executeRuntimeTool("system.echo", request);
        verify(runtimeProxyClient).executeRuntimeComposition("system.flow", request);
        verify(runtimeProxyClient).resumeRuntimeInteraction("session-1", request);
    }

    @Test
    void delegatesRuntimeDebugSessionsToRuntimeService() {
        RuntimeProxyClient runtimeProxyClient = mock(RuntimeProxyClient.class);
        RuntimeCompatibilityController controller = new RuntimeCompatibilityController(runtimeProxyClient);
        Map<String, Object> request = Map.of("targetType", "WORKFLOW_DRAFT");
        ResponseEntity<Object> pending = ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED)
                .body(Map.of("code", "RUNTIME_DEBUG_SESSION_PENDING"));
        when(runtimeProxyClient.createRuntimeDebugSession(request)).thenReturn(pending);
        when(runtimeProxyClient.getRuntimeDebugSession("s1")).thenReturn(pending);
        when(runtimeProxyClient.submitRuntimeDebugSession("s1", request)).thenReturn(pending);
        when(runtimeProxyClient.cancelRuntimeDebugSession("s1")).thenReturn(pending);

        assertEquals(pending, controller.createRuntimeDebugSession(request));
        assertEquals(pending, controller.getRuntimeDebugSession("s1"));
        assertEquals(pending, controller.submitRuntimeDebugSession("s1", request));
        assertEquals(pending, controller.cancelRuntimeDebugSession("s1"));
        verify(runtimeProxyClient).createRuntimeDebugSession(request);
        verify(runtimeProxyClient).getRuntimeDebugSession("s1");
        verify(runtimeProxyClient).submitRuntimeDebugSession("s1", request);
        verify(runtimeProxyClient).cancelRuntimeDebugSession("s1");
    }

    @Test
    void delegatesAgentInteractionsToRuntimeService() {
        RuntimeProxyClient runtimeProxyClient = mock(RuntimeProxyClient.class);
        RuntimeCompatibilityController controller = new RuntimeCompatibilityController(runtimeProxyClient);
        Map<String, Object> request = Map.of("decision", "approve");
        ResponseEntity<Object> pending = ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED)
                .body(Map.of("code", "RUNTIME_AGENT_INTERACTION_PENDING"));
        when(runtimeProxyClient.listHumanApprovals(7L, "user-1", 25)).thenReturn(pending);
        when(runtimeProxyClient.submitHumanApproval("ix-1", request)).thenReturn(pending);
        when(runtimeProxyClient.cancelHumanApproval("ix-1", "user-1")).thenReturn(pending);

        assertEquals(pending, controller.listHumanApprovals(7L, "user-1", 25));
        assertEquals(pending, controller.submitHumanApproval("ix-1", request));
        assertEquals(pending, controller.cancelHumanApproval("ix-1", "user-1"));
        verify(runtimeProxyClient).listHumanApprovals(7L, "user-1", 25);
        verify(runtimeProxyClient).submitHumanApproval("ix-1", request);
        verify(runtimeProxyClient).cancelHumanApproval("ix-1", "user-1");
    }
}
