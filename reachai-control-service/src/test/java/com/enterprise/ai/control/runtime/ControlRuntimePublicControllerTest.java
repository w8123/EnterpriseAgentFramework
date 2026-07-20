package com.enterprise.ai.control.runtime;

import com.enterprise.ai.control.client.runtime.RuntimeProxyClient;
import com.enterprise.ai.control.client.runtime.RuntimeTrustedAgentExecutionGateway;
import com.enterprise.ai.control.identity.PlatformBearerAuthService;
import com.enterprise.ai.control.identity.PlatformUserEntity;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;

import java.io.ByteArrayOutputStream;
import java.lang.reflect.Method;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ControlRuntimePublicControllerTest {

    @Test
    void keepsPublicRuntimeRouteShapeOnControlService() throws Exception {
        Method executeAgent = ControlRuntimePublicController.class
                .getDeclaredMethod("executeAgent", String.class, Map.class);
        Method executeAgentDetailed = ControlRuntimePublicController.class
                .getDeclaredMethod("executeAgentDetailed", String.class, Map.class);
        Method executeAgentStream = ControlRuntimePublicController.class
                .getDeclaredMethod("executeAgentStream", String.class, Map.class);
        Method clearAgentSession = ControlRuntimePublicController.class
                .getDeclaredMethod("clearAgentSession", String.class);
        Method routeEvaluation = ControlRuntimePublicController.class.getDeclaredMethod("routeEvaluation", int.class);
        Method getTrace = ControlRuntimePublicController.class.getDeclaredMethod("getTrace", String.class);
        Method listRecentTraces = ControlRuntimePublicController.class
                .getDeclaredMethod("listRecentTraces", String.class, int.class, int.class);
        Method runOpsDetail = ControlRuntimePublicController.class.getDeclaredMethod("runOpsDetail", String.class);
        Method runOpsRecent = ControlRuntimePublicController.class
                .getDeclaredMethod("runOpsRecent", String.class, String.class, String.class, String.class,
                        String.class, String.class, String.class, int.class, int.class);
        Method runOpsDiagnostics = ControlRuntimePublicController.class
                .getDeclaredMethod("runOpsDiagnostics", String.class, String.class, String.class, String.class,
                        String.class, String.class, String.class, int.class, int.class);
        Method runOpsCompare = ControlRuntimePublicController.class
                .getDeclaredMethod("runOpsCompare", String.class, String.class);
        Method runOpsReplay = ControlRuntimePublicController.class
                .getDeclaredMethod("runOpsReplay", String.class, Map.class);
        Method gatewayAgentChat = ControlRuntimePublicController.class
                .getDeclaredMethod("gatewayAgentChat", String.class, Map.class);
        Method gatewayCatalog = ControlRuntimePublicController.class
                .getDeclaredMethod("gatewayCatalog", Long.class);
        Method listWorkflows = ControlRuntimePublicController.class
                .getDeclaredMethod("listWorkflows", Long.class, String.class, String.class, String.class);
        Method createWorkflow = ControlRuntimePublicController.class
                .getDeclaredMethod("createWorkflow", Map.class);
        Method getWorkflow = ControlRuntimePublicController.class.getDeclaredMethod("getWorkflow", String.class);
        Method updateWorkflow = ControlRuntimePublicController.class
                .getDeclaredMethod("updateWorkflow", String.class, Map.class);
        Method deleteWorkflow = ControlRuntimePublicController.class.getDeclaredMethod("deleteWorkflow", String.class);
        Method graphNodeTypes = ControlRuntimePublicController.class.getDeclaredMethod("graphNodeTypes");
        Method validateWorkflowRuntime = ControlRuntimePublicController.class
                .getDeclaredMethod("validateWorkflowRuntime", Map.class);
        Method workflowStudio = ControlRuntimePublicController.class.getDeclaredMethod("workflowStudio", String.class);
        Method saveWorkflowStudio = ControlRuntimePublicController.class
                .getDeclaredMethod("saveWorkflowStudio", String.class, Map.class);
        Method debugWorkflowNode = ControlRuntimePublicController.class
                .getDeclaredMethod("debugWorkflowNode", Map.class);
        Method debugWorkflowRun = ControlRuntimePublicController.class
                .getDeclaredMethod("debugWorkflowRun", Map.class);
        Method generateWorkflowStudioDraft = ControlRuntimePublicController.class
                .getDeclaredMethod("generateWorkflowStudioDraft", Map.class);
        Method editWorkflowStudioDraft = ControlRuntimePublicController.class
                .getDeclaredMethod("editWorkflowStudioDraft", Map.class);
        Method createWorkflowAiCodingWorkflow = ControlRuntimePublicController.class
                .getDeclaredMethod("createWorkflowAiCodingWorkflow", Map.class, String.class);
        Method workflowAiCodingContext = ControlRuntimePublicController.class
                .getDeclaredMethod("workflowAiCodingContext", String.class);
        Method validateWorkflowAiCoding = ControlRuntimePublicController.class
                .getDeclaredMethod("validateWorkflowAiCoding", String.class, Map.class);
        Method patchWorkflowAiCoding = ControlRuntimePublicController.class
                .getDeclaredMethod("patchWorkflowAiCoding", String.class, Map.class);
        Method runWorkflowAiCoding = ControlRuntimePublicController.class
                .getDeclaredMethod("runWorkflowAiCoding", String.class, Map.class);
        Method workflowAiCodingVersions = ControlRuntimePublicController.class
                .getDeclaredMethod("workflowAiCodingVersions", String.class);
        Method publishWorkflowAiCoding = ControlRuntimePublicController.class
                .getDeclaredMethod("publishWorkflowAiCoding", String.class, Map.class);
        Method workflowAiCodingRuns = ControlRuntimePublicController.class
                .getDeclaredMethod("workflowAiCodingRuns", String.class, Integer.class, Integer.class);
        Method workflowAiCodingRunDetail = ControlRuntimePublicController.class
                .getDeclaredMethod("workflowAiCodingRunDetail", String.class, String.class);
        Method workflowAiCodingPageAssistantCatalog = ControlRuntimePublicController.class
                .getDeclaredMethod("workflowAiCodingPageAssistantCatalog", String.class);
        Method validateWorkflowAiCodingPageAssistant = ControlRuntimePublicController.class
                .getDeclaredMethod("validateWorkflowAiCodingPageAssistant", String.class, Map.class);
        Method smokeTestWorkflowAiCodingPageAssistant = ControlRuntimePublicController.class
                .getDeclaredMethod("smokeTestWorkflowAiCodingPageAssistant", String.class, Map.class);
        Method listWorkflowVersions = ControlRuntimePublicController.class
                .getDeclaredMethod("listWorkflowVersions", String.class);
        Method publishWorkflowVersion = ControlRuntimePublicController.class
                .getDeclaredMethod("publishWorkflowVersion", String.class, Map.class);
        Method publishWorkflowVersionExplicit = ControlRuntimePublicController.class
                .getDeclaredMethod("publishWorkflowVersionExplicit", String.class, Map.class);
        Method validateWorkflowVersion = ControlRuntimePublicController.class
                .getDeclaredMethod("validateWorkflowVersion", String.class);
        Method rollbackWorkflowVersion = ControlRuntimePublicController.class
                .getDeclaredMethod("rollbackWorkflowVersion", String.class, Long.class, Map.class);
        Method attachPageAssistantWorkflowTool = ControlRuntimePublicController.class
                .getDeclaredMethod("attachPageAssistantWorkflowTool", String.class, Map.class);
        Method listWorkflowCredentials = ControlRuntimePublicController.class
                .getDeclaredMethod("listWorkflowCredentials", Long.class, String.class);
        Method createWorkflowCredential = ControlRuntimePublicController.class
                .getDeclaredMethod("createWorkflowCredential", Map.class);
        Method updateWorkflowCredential = ControlRuntimePublicController.class
                .getDeclaredMethod("updateWorkflowCredential", Long.class, Map.class);
        Method deleteWorkflowCredential = ControlRuntimePublicController.class
                .getDeclaredMethod("deleteWorkflowCredential", Long.class);
        Method listAgents = ControlRuntimePublicController.class
                .getDeclaredMethod("listAgents", Long.class, String.class);
        Method createAgent = ControlRuntimePublicController.class.getDeclaredMethod("createAgent", Map.class);
        Method getAgent = ControlRuntimePublicController.class.getDeclaredMethod("getAgent", String.class);
        Method updateAgent = ControlRuntimePublicController.class.getDeclaredMethod("updateAgent", String.class, Map.class);
        Method deleteAgent = ControlRuntimePublicController.class.getDeclaredMethod("deleteAgent", String.class);
        Method executeRuntimeTool = ControlRuntimePublicController.class
                .getDeclaredMethod("executeRuntimeTool", String.class, Map.class);
        Method executeRuntimeComposition = ControlRuntimePublicController.class
                .getDeclaredMethod("executeRuntimeComposition", String.class, Map.class);
        Method resumeRuntimeInteraction = ControlRuntimePublicController.class
                .getDeclaredMethod("resumeRuntimeInteraction", String.class, Map.class);
        Method createRuntimeDebugSession = ControlRuntimePublicController.class
                .getDeclaredMethod("createRuntimeDebugSession", Map.class);
        Method getRuntimeDebugSession = ControlRuntimePublicController.class
                .getDeclaredMethod("getRuntimeDebugSession", String.class);
        Method submitRuntimeDebugSession = ControlRuntimePublicController.class
                .getDeclaredMethod("submitRuntimeDebugSession", String.class, Map.class);
        Method cancelRuntimeDebugSession = ControlRuntimePublicController.class
                .getDeclaredMethod("cancelRuntimeDebugSession", String.class);
        Method createRuntimeDebugSessionStream = ControlRuntimePublicController.class
                .getDeclaredMethod("createRuntimeDebugSessionStream", Map.class);
        Method submitRuntimeDebugSessionStream = ControlRuntimePublicController.class
                .getDeclaredMethod("submitRuntimeDebugSessionStream", String.class, Map.class);
        Method listHumanApprovals = ControlRuntimePublicController.class
                .getDeclaredMethod("listHumanApprovals", String.class, String.class, int.class);
        Method submitHumanApproval = ControlRuntimePublicController.class
                .getDeclaredMethod("submitHumanApproval", String.class, Map.class);
        Method cancelHumanApproval = ControlRuntimePublicController.class
                .getDeclaredMethod("cancelHumanApproval", String.class, String.class);
        Method listGuardDecisions = ControlRuntimePublicController.class
                .getDeclaredMethod("listGuardDecisions", String.class, String.class, String.class, String.class,
                        String.class, String.class, String.class, int.class);

        assertArrayEquals(new String[] {"/api/runtime/agents/execute"},
                executeAgent.getAnnotation(PostMapping.class).value());
        assertArrayEquals(new String[] {"/api/runtime/agents/execute/detailed"},
                executeAgentDetailed.getAnnotation(PostMapping.class).value());
        assertArrayEquals(new String[] {"/api/runtime/agents/execute/stream"},
                executeAgentStream.getAnnotation(PostMapping.class).value());
        assertArrayEquals(new String[] {"/api/runtime/agents/sessions/{sessionId}"},
                clearAgentSession.getAnnotation(DeleteMapping.class).value());
        assertArrayEquals(new String[] {"/api/runtime/agents/route-evaluation"},
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
        assertArrayEquals(new String[] {"/api/v1/agents/{key}/chat", "/gateway/agents/{key}/chat"},
                gatewayAgentChat.getAnnotation(PostMapping.class).value());
        assertArrayEquals(new String[] {"/gateway/catalog"},
                gatewayCatalog.getAnnotation(GetMapping.class).value());
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
        assertArrayEquals(new String[] {"/api/workflows/{id}/page-assistant/attach-tool"},
                attachPageAssistantWorkflowTool.getAnnotation(PostMapping.class).value());
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
        assertArrayEquals(new String[] {"/api/runtime/debug-sessions/stream"},
                createRuntimeDebugSessionStream.getAnnotation(PostMapping.class).value());
        assertArrayEquals(new String[] {"/api/runtime/debug-sessions/{sessionId}/submit/stream"},
                submitRuntimeDebugSessionStream.getAnnotation(PostMapping.class).value());
        assertArrayEquals(new String[] {"/api/runtime/interactions/human-approvals"},
                listHumanApprovals.getAnnotation(GetMapping.class).value());
        assertArrayEquals(new String[] {"/api/runtime/interactions/human-approvals/{interactionId}/submit"},
                submitHumanApproval.getAnnotation(PostMapping.class).value());
        assertArrayEquals(new String[] {"/api/runtime/interactions/human-approvals/{interactionId}"},
                cancelHumanApproval.getAnnotation(DeleteMapping.class).value());
        assertArrayEquals(new String[] {"/api/trace-center/guard-decisions"},
                listGuardDecisions.getAnnotation(GetMapping.class).value());
    }

    @Test
    void delegatesTraceCenterGuardDecisionsToRuntimeService() {
        RuntimeProxyClient runtimeProxyClient = mock(RuntimeProxyClient.class);
        ControlRuntimePublicController controller = new ControlRuntimePublicController(runtimeProxyClient);
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
        ControlRuntimePublicController controller = new ControlRuntimePublicController(runtimeProxyClient);
        Map<String, Object> request = Map.of("agentId", "agent-1", "input", "hello");
        ResponseEntity<Map<String, Object>> delegated = ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(Map.of("traceId", "trace-1", "status", "RUNNING"));
        when(runtimeProxyClient.executeAgent(request)).thenReturn(delegated);

        ResponseEntity<Map<String, Object>> response = controller.executeAgent(null, request);

        assertEquals(delegated, response);
        verify(runtimeProxyClient).executeAgent(request);
    }

    @Test
    void delegatesDetailedAgentExecutionToRuntimeService() {
        RuntimeProxyClient runtimeProxyClient = mock(RuntimeProxyClient.class);
        ControlRuntimePublicController controller = new ControlRuntimePublicController(runtimeProxyClient);
        Map<String, Object> request = Map.of("agentId", "agent-1", "message", "hello");
        ResponseEntity<Map<String, Object>> delegated = ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED)
                .body(Map.of("code", "RUNTIME_AGENT_EXECUTION_PENDING"));
        when(runtimeProxyClient.executeAgentDetailed(request)).thenReturn(delegated);

        ResponseEntity<Map<String, Object>> response = controller.executeAgentDetailed(null, request);

        assertEquals(delegated, response);
        verify(runtimeProxyClient).executeAgentDetailed(request);
    }

    @Test
    void delegatesTraceLookupToRuntimeService() {
        RuntimeProxyClient runtimeProxyClient = mock(RuntimeProxyClient.class);
        ControlRuntimePublicController controller = new ControlRuntimePublicController(runtimeProxyClient);
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
        ControlRuntimePublicController controller = new ControlRuntimePublicController(runtimeProxyClient);
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
        ControlRuntimePublicController controller = new ControlRuntimePublicController(runtimeProxyClient);
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
        ControlRuntimePublicController controller = new ControlRuntimePublicController(runtimeProxyClient);
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
        ControlRuntimePublicController controller = new ControlRuntimePublicController(runtimeProxyClient);
        ResponseEntity<List<Map<String, Object>>> delegated = ResponseEntity.ok(
                List.of(Map.of("traceId", "trace-1")));
        when(runtimeProxyClient.runOpsRecent(
                "qmssmp", "SUCCESS", "AGENT", "DEBUG", "agent-1", "user-1", null, 25, 14))
                .thenReturn(delegated);

        ResponseEntity<List<Map<String, Object>>> response = controller.runOpsRecent(
                "qmssmp", "SUCCESS", "AGENT", "DEBUG", "agent-1", "user-1", null, 25, 14);

        assertEquals(delegated, response);
        verify(runtimeProxyClient).runOpsRecent(
                "qmssmp", "SUCCESS", "AGENT", "DEBUG", "agent-1", "user-1", null, 25, 14);
    }

    @Test
    void delegatesRunOpsCompareToRuntimeService() {
        RuntimeProxyClient runtimeProxyClient = mock(RuntimeProxyClient.class);
        ControlRuntimePublicController controller = new ControlRuntimePublicController(runtimeProxyClient);
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
        ControlRuntimePublicController controller = new ControlRuntimePublicController(runtimeProxyClient);
        ResponseEntity<Map<String, Object>> delegated = ResponseEntity.ok(Map.of(
                "failureClusters", java.util.List.of(),
                "versionComparisons", java.util.List.of()
        ));
        when(runtimeProxyClient.runOpsDiagnostics(
                "qmssmp", "FAILED", "AGENT", "EMBED", "agent-1", "user-1", null, 25, 14))
                .thenReturn(delegated);

        ResponseEntity<Map<String, Object>> response = controller.runOpsDiagnostics(
                "qmssmp", "FAILED", "AGENT", "EMBED", "agent-1", "user-1", null, 25, 14);

        assertEquals(delegated, response);
        verify(runtimeProxyClient).runOpsDiagnostics(
                "qmssmp", "FAILED", "AGENT", "EMBED", "agent-1", "user-1", null, 25, 14);
    }

    @Test
    void delegatesRunOpsReplayToRuntimeService() {
        RuntimeProxyClient runtimeProxyClient = mock(RuntimeProxyClient.class);
        ControlRuntimePublicController controller = new ControlRuntimePublicController(runtimeProxyClient);
        Map<String, Object> request = Map.of("messageOverride", "replay this");
        ResponseEntity<Map<String, Object>> delegated = ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED)
                .body(Map.of("code", "RUNTIME_RUNOPS_REPLAY_PENDING"));
        when(runtimeProxyClient.runOpsReplay("trace-1", request)).thenReturn(delegated);

        ResponseEntity<Map<String, Object>> response = controller.runOpsReplay("trace-1", request);

        assertEquals(delegated, response);
        verify(runtimeProxyClient).runOpsReplay("trace-1", request);
    }

    @Test
    void relaysAgentExecutionStreamWithoutFeignBuffering() throws Exception {
        RuntimeProxyClient runtimeProxyClient = mock(RuntimeProxyClient.class);
        RuntimeAgentStreamProxy streamProxy = mock(RuntimeAgentStreamProxy.class);
        ControlRuntimePublicController controller = new ControlRuntimePublicController(
                runtimeProxyClient, null, streamProxy);
        Map<String, Object> request = Map.of("message", "hello");
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        ResponseEntity<org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody> response =
                controller.executeAgentStream(null, request);
        response.getBody().writeTo(output);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("no-cache, no-transform", response.getHeaders().getCacheControl());
        assertEquals("no", response.getHeaders().getFirst("X-Accel-Buffering"));
        // Without Bearer, stream stays on the public untrusted Runtime path.
        verify(streamProxy).stream(request, output);
    }

    @Test
    void agentSyncWithBearerUsesSignedTrustedGateway() {
        RuntimeProxyClient runtimeProxyClient = mock(RuntimeProxyClient.class);
        RuntimeTrustedAgentExecutionGateway gateway = mock(RuntimeTrustedAgentExecutionGateway.class);
        PlatformBearerAuthService bearerAuthService = mock(PlatformBearerAuthService.class);
        PlatformUserEntity user = new PlatformUserEntity();
        user.setId(42L);
        user.setStatus("ACTIVE");
        when(bearerAuthService.resolveBearerUser("Bearer tok")).thenReturn(Optional.of(user));
        when(gateway.executeTrusted(any(), eq("AGENT"), eq("42")))
                .thenReturn(ResponseEntity.ok(Map.of("success", true, "answer", "ok")));
        ControlRuntimePublicController controller = new ControlRuntimePublicController(
                runtimeProxyClient, null, null, gateway, bearerAuthService);

        ResponseEntity<Map<String, Object>> response = controller.executeAgent(
                "Bearer tok", Map.of("agentId", "a1", "message", "hi", "userId", "attacker"));

        assertEquals(HttpStatus.OK, response.getStatusCode());
        verify(gateway).executeTrusted(any(), eq("AGENT"), eq("42"));
        verify(runtimeProxyClient, never()).executeAgent(any());
    }

    @Test
    void agentStreamWithBearerUsesSignedInternalStream() throws Exception {
        RuntimeProxyClient runtimeProxyClient = mock(RuntimeProxyClient.class);
        RuntimeAgentStreamProxy streamProxy = mock(RuntimeAgentStreamProxy.class);
        RuntimeTrustedAgentExecutionGateway gateway = mock(RuntimeTrustedAgentExecutionGateway.class);
        PlatformBearerAuthService bearerAuthService = mock(PlatformBearerAuthService.class);
        PlatformUserEntity user = new PlatformUserEntity();
        user.setId(42L);
        user.setStatus("ACTIVE");
        when(bearerAuthService.resolveBearerUser("Bearer tok")).thenReturn(Optional.of(user));
        ControlRuntimePublicController controller = new ControlRuntimePublicController(
                runtimeProxyClient, null, streamProxy, gateway, bearerAuthService);
        Map<String, Object> request = Map.of("agentId", "a1", "message", "hello");
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        controller.executeAgentStream("Bearer tok", request).getBody().writeTo(output);

        verify(gateway).streamTrusted(eq(request), eq("AGENT"), eq("42"), eq(output), any());
        verify(streamProxy, never()).stream(any(Map.class), any());
    }

    @Test
    void relaysDebugSessionStreamsWithoutFeignBuffering() throws Exception {
        RuntimeProxyClient runtimeProxyClient = mock(RuntimeProxyClient.class);
        RuntimeAgentStreamProxy streamProxy = mock(RuntimeAgentStreamProxy.class);
        ControlRuntimePublicController controller = new ControlRuntimePublicController(
                runtimeProxyClient, null, streamProxy);
        Map<String, Object> request = Map.of("targetType", "WORKFLOW_DRAFT");
        ByteArrayOutputStream createOutput = new ByteArrayOutputStream();
        ByteArrayOutputStream submitOutput = new ByteArrayOutputStream();

        controller.createRuntimeDebugSessionStream(request).getBody().writeTo(createOutput);
        controller.submitRuntimeDebugSessionStream("session-1", request).getBody().writeTo(submitOutput);

        verify(streamProxy).stream(RuntimeAgentStreamProxy.DEBUG_SESSION_STREAM, request, createOutput);
        verify(streamProxy).streamDebugSubmit("session-1", request, submitOutput);
    }

    @Test
    void delegatesAgentSessionClearToRuntimeService() {
        RuntimeProxyClient runtimeProxyClient = mock(RuntimeProxyClient.class);
        ControlRuntimePublicController controller = new ControlRuntimePublicController(runtimeProxyClient);
        ResponseEntity<Void> cleared = ResponseEntity.noContent().build();
        when(runtimeProxyClient.clearAgentSession("s1")).thenReturn(cleared);

        assertEquals(cleared, controller.clearAgentSession("s1"));
        verify(runtimeProxyClient).clearAgentSession("s1");
    }

    @Test
    void delegatesGatewayAgentChatToRuntimeChatWithoutRetiredPlatformProxy() {
        RuntimeProxyClient runtimeProxyClient = mock(RuntimeProxyClient.class);
        ControlRuntimePublicController controller = new ControlRuntimePublicController(runtimeProxyClient);
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("message", "hello");
        request.put("sessionId", "s1");
        request.put("userId", "u1");
        Map<String, Object> runtimeRequest = new LinkedHashMap<>(request);
        runtimeRequest.put("agentId", "orders-bot");
        runtimeRequest.put("intentHint", "AGENT_GATEWAY_CHAT");
        runtimeRequest.put("entryType", "GATEWAY");
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
        assertFalse(request.containsKey("agentId"));
        assertFalse(request.containsKey("intentHint"));
        verify(runtimeProxyClient).executeAgent(runtimeRequest);
    }

    @Test
    @SuppressWarnings("unchecked")
    void delegatesGatewayCatalogToRuntimeAgentCatalogWithoutRetiredPlatformProxy() {
        RuntimeProxyClient runtimeProxyClient = mock(RuntimeProxyClient.class);
        ControlRuntimePublicController controller = new ControlRuntimePublicController(runtimeProxyClient);
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
        when(runtimeProxyClient.listAgents(7L, null))
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
        verify(runtimeProxyClient).listAgents(7L, null);
    }

    @Test
    void delegatesWorkflowCrudToRuntimeService() {
        RuntimeProxyClient runtimeProxyClient = mock(RuntimeProxyClient.class);
        ControlRuntimePublicController controller = new ControlRuntimePublicController(runtimeProxyClient);
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
        ControlRuntimePublicController controller = new ControlRuntimePublicController(runtimeProxyClient);
        ResponseEntity<Object> delegated = ResponseEntity.ok(java.util.List.of(Map.of("type", "LLM")));
        when(runtimeProxyClient.graphNodeTypes()).thenReturn(delegated);

        ResponseEntity<Object> response = controller.graphNodeTypes();

        assertEquals(delegated, response);
        verify(runtimeProxyClient).graphNodeTypes();
    }

    @Test
    void delegatesWorkflowRuntimeValidationToRuntimeService() {
        RuntimeProxyClient runtimeProxyClient = mock(RuntimeProxyClient.class);
        ControlRuntimePublicController controller = new ControlRuntimePublicController(runtimeProxyClient);
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
        ControlRuntimePublicController controller = new ControlRuntimePublicController(runtimeProxyClient);
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
        ControlRuntimePublicController controller = new ControlRuntimePublicController(runtimeProxyClient);
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
        ControlRuntimePublicController controller = new ControlRuntimePublicController(runtimeProxyClient);
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
        com.enterprise.ai.control.aiassist.ControlAiCodingAccessGuard aiCodingAccessGuard =
                mock(com.enterprise.ai.control.aiassist.ControlAiCodingAccessGuard.class);
        ControlRuntimePublicController controller = new ControlRuntimePublicController(runtimeProxyClient, aiCodingAccessGuard);
        Map<String, Object> request = Map.of("projectId", 7L, "instruction", "add guard");
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

        assertEquals(pending, controller.createWorkflowAiCodingWorkflow(request, "rac_secret"));
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
        verify(aiCodingAccessGuard).requireWorkflowCreateAccess(request, "rac_secret");
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
        ControlRuntimePublicController controller = new ControlRuntimePublicController(runtimeProxyClient);
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
    void delegatesPageAssistantWorkflowAttachmentToRuntimeService() {
        RuntimeProxyClient runtimeProxyClient = mock(RuntimeProxyClient.class);
        ControlRuntimePublicController controller = new ControlRuntimePublicController(runtimeProxyClient);
        Map<String, Object> request = Map.of(
                "projectCode", "orders",
                "agentId", "agent-1",
                "pageKey", "orders.list");
        ResponseEntity<Object> delegated = ResponseEntity.ok(Map.of(
                "agentId", "agent-1",
                "workflowId", "wf-1",
                "toolName", "orders_page_assistant",
                "configStatus", "ACTIVE"));
        when(runtimeProxyClient.attachPageAssistantWorkflowTool("wf-1", request)).thenReturn(delegated);

        ResponseEntity<Object> attachment = controller.attachPageAssistantWorkflowTool("wf-1", request);

        assertEquals(delegated, attachment);
        verify(runtimeProxyClient).attachPageAssistantWorkflowTool("wf-1", request);
    }

    @Test
    void delegatesWorkflowCredentialOperationsToRuntimeService() {
        RuntimeProxyClient runtimeProxyClient = mock(RuntimeProxyClient.class);
        ControlRuntimePublicController controller = new ControlRuntimePublicController(runtimeProxyClient);
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
    void delegatesAgentCrudToRuntimeService() {
        RuntimeProxyClient runtimeProxyClient = mock(RuntimeProxyClient.class);
        ControlRuntimePublicController controller = new ControlRuntimePublicController(runtimeProxyClient);
        Map<String, Object> request = Map.of("id", "agent-1", "keySlug", "orders-agent", "name", "Orders Agent");
        ResponseEntity<Object> list = ResponseEntity.ok(java.util.List.of(request));
        ResponseEntity<Object> statistics = ResponseEntity.ok(Map.of(
                "totalAgents", 1,
                "enabledAgents", 1,
                "workflowToolAgents", 1));
        ResponseEntity<Object> created = ResponseEntity.ok(request);
        ResponseEntity<Object> found = ResponseEntity.ok(request);
        ResponseEntity<Object> updated = ResponseEntity.ok(request);
        ResponseEntity<Object> deleted = ResponseEntity.noContent().build();
        when(runtimeProxyClient.listAgents(7L, "orders")).thenReturn(list);
        when(runtimeProxyClient.getAgentStatistics(7L, "orders")).thenReturn(statistics);
        when(runtimeProxyClient.createAgent(request)).thenReturn(created);
        when(runtimeProxyClient.getAgent("agent-1")).thenReturn(found);
        when(runtimeProxyClient.updateAgent("agent-1", request)).thenReturn(updated);
        when(runtimeProxyClient.deleteAgent("agent-1")).thenReturn(deleted);

        assertEquals(list, controller.listAgents(7L, "orders"));
        assertEquals(statistics, controller.getAgentStatistics(7L, "orders"));
        assertEquals(created, controller.createAgent(request));
        assertEquals(found, controller.getAgent("agent-1"));
        assertEquals(updated, controller.updateAgent("agent-1", request));
        assertEquals(deleted, controller.deleteAgent("agent-1"));
        verify(runtimeProxyClient).listAgents(7L, "orders");
        verify(runtimeProxyClient).getAgentStatistics(7L, "orders");
        verify(runtimeProxyClient).createAgent(request);
        verify(runtimeProxyClient).getAgent("agent-1");
        verify(runtimeProxyClient).updateAgent("agent-1", request);
        verify(runtimeProxyClient).deleteAgent("agent-1");
    }

    @Test
    void delegatesRuntimeCapabilityExecutionToRuntimeService() {
        RuntimeProxyClient runtimeProxyClient = mock(RuntimeProxyClient.class);
        ControlRuntimePublicController controller = new ControlRuntimePublicController(runtimeProxyClient);
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
    void rejectsWorkflowInteractionResumeOnCompatibilityEndpoint() {
        RuntimeProxyClient runtimeProxyClient = mock(RuntimeProxyClient.class);
        ControlRuntimePublicController controller = new ControlRuntimePublicController(runtimeProxyClient);
        Map<String, Object> request = Map.of("values", Map.of("q", "x"));

        ResponseEntity<Object> response = controller.resumeRuntimeInteraction("wfi_abc123", request);

        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
        assertTrue(response.getBody() instanceof Map<?, ?>);
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertEquals("RUNTIME_INTERACTION_FORBIDDEN", body.get("code"));
        verify(runtimeProxyClient, never()).resumeRuntimeInteraction(any(), any());
    }

    @Test
    void delegatesRuntimeDebugSessionsToRuntimeService() {
        RuntimeProxyClient runtimeProxyClient = mock(RuntimeProxyClient.class);
        ControlRuntimePublicController controller = new ControlRuntimePublicController(runtimeProxyClient);
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
        ControlRuntimePublicController controller = new ControlRuntimePublicController(runtimeProxyClient);
        Map<String, Object> request = Map.of("decision", "approve");
        ResponseEntity<Object> pending = ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED)
                .body(Map.of("code", "RUNTIME_AGENT_INTERACTION_PENDING"));
        when(runtimeProxyClient.listHumanApprovals("agent-7", "user-1", 25)).thenReturn(pending);
        when(runtimeProxyClient.submitHumanApproval("ix-1", request)).thenReturn(pending);
        when(runtimeProxyClient.cancelHumanApproval("ix-1", "user-1")).thenReturn(pending);

        assertEquals(pending, controller.listHumanApprovals("agent-7", "user-1", 25));
        assertEquals(pending, controller.submitHumanApproval("ix-1", request));
        assertEquals(pending, controller.cancelHumanApproval("ix-1", "user-1"));
        verify(runtimeProxyClient).listHumanApprovals("agent-7", "user-1", 25);
        verify(runtimeProxyClient).submitHumanApproval("ix-1", request);
        verify(runtimeProxyClient).cancelHumanApproval("ix-1", "user-1");
    }
}
