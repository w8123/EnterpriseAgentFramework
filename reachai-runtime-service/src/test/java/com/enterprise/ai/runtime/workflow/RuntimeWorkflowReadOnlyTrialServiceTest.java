package com.enterprise.ai.runtime.workflow;

import com.enterprise.ai.common.capability.ConsoleCapabilityInvocationContracts;
import com.enterprise.ai.common.capability.WorkflowReadOnlyTrialPolicy;
import com.enterprise.ai.runtime.client.capability.RuntimeCapabilityCatalogClient;
import com.enterprise.ai.runtime.identity.WorkflowExecutionIdentity;
import com.enterprise.ai.runtime.internalauth.VerifiedInternalServiceAuth;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RuntimeWorkflowReadOnlyTrialServiceTest {
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private final RuntimeWorkflowDefinitionService definitions = mock(RuntimeWorkflowDefinitionService.class);
    private final RuntimeWorkflowHttpApiService http = mock(RuntimeWorkflowHttpApiService.class);
    private final RuntimeWorkflowDebugService debug = mock(RuntimeWorkflowDebugService.class);
    private final RuntimeCapabilityCatalogClient capabilities = mock(RuntimeCapabilityCatalogClient.class);
    private final RuntimeWorkflowDocumentCanonicalizer canonicalizer = mock(RuntimeWorkflowDocumentCanonicalizer.class);
    private final RuntimeWorkflowReadOnlyTrialService service = new RuntimeWorkflowReadOnlyTrialService(definitions, http,
            capabilities, debug, canonicalizer, json);

    /** USER_INPUT → method → PARAMETER_EXTRACT → API → ANSWER: the live-test multi-step shape. */
    private static final String GRAPH = """
            {"schemaVersion":2,"nodes":[
             {"id":"input","type":"USER_INPUT","config":{}},
             {"id":"method","name":"查询产品","type":"TOOL","ref":{"kind":"TOOL","qualifiedName":"orders:normalize"},
              "config":{"inputMapping":{"orderNo":"params.orderNo"}}},
             {"id":"extract","name":"提取产品","type":"PARAMETER_EXTRACT","config":{}},
             {"id":"api","name":"查询库存","type":"TOOL","ref":{"kind":"TOOL","qualifiedName":"http-api:orders:dev:api"},
              "config":{"httpApiAssetId":1,"inputMapping":{"pathParams.id":"var.productId"}}},
             {"id":"answer","type":"ANSWER","config":{}}],
             "edges":[],"entryNodeId":"input","exitNodeIds":["answer"]}
            """;
    private static final String HASH = WorkflowReadOnlyTrialPolicy.graphSha256(GRAPH);
    private RuntimeWorkflowDefinitionEntity saved;
    private RuntimeWorkflowHttpApiService.DraftPin apiPin;

    @BeforeEach void prepare() {
        saved = new RuntimeWorkflowDefinitionEntity();
        saved.setId("wf-api"); saved.setProjectId(41L); saved.setProjectCode("orders"); saved.setStatus("DRAFT");
        saved.setUpdatedAt(LocalDateTime.parse("2026-09-30T10:00:00")); saved.setGraphSpecJson(GRAPH);
        when(definitions.findById("wf-api")).thenAnswer(call -> Optional.of(saved));
        when(canonicalizer.canonicalizeGraphSpecJson(anyString())).thenAnswer(call -> call.getArgument(0));
        apiPin = new RuntimeWorkflowHttpApiService.DraftPin("wf-api", revision(), "api", 1L, "http-api:orders:dev:api",
                41L, "orders", "dev", "a".repeat(64), "b".repeat(64), 3L, null);
        when(http.prepareDraft(any(), any(), anyString(), any())).thenReturn(apiPin);
        when(capabilities.getBusinessMethodExecutionContext("orders:normalize", "orders")).thenReturn(methodOwner());
    }

    private String revision() { return "2026-09-30T10:00"; }

    private ConsoleCapabilityInvocationContracts.InvocationContext methodOwner() {
        return new ConsoleCapabilityInvocationContracts.InvocationContext(1, "orders_normalize", "orders:normalize",
                "orders:normalize", "BUSINESS_METHOD", 41L, "orders", "a".repeat(64), "a".repeat(64), "a".repeat(64),
                "READY", true, "READ_ONLY", List.of(new ConsoleCapabilityInvocationContracts.Parameter("orderNo", "String",
                null, true, "BODY", List.of(), Map.of())), null, "String", null, null, "UNKNOWN", true, false, true, null, null, 30_000, "d".repeat(64));
    }

    private List<WorkflowReadOnlyTrialPolicy.AllowedTarget> allowed() {
        return List.of(WorkflowReadOnlyTrialPolicy.AllowedTarget.businessMethod(
                        new WorkflowReadOnlyTrialPolicy.Target("method", "查询产品", 0, "orders:normalize", "BUSINESS_METHOD"), methodOwner()),
                WorkflowReadOnlyTrialPolicy.AllowedTarget.httpApi(
                        new WorkflowReadOnlyTrialPolicy.Target("api", "查询库存", 1, "http-api:orders:dev:api", "HTTP_API"),
                        "dev", "a".repeat(64), "b".repeat(64)));
    }

    private WorkflowReadOnlyTrialPolicy.TrialCommand command(long projectId, String revision, String hash,
                                                             List<WorkflowReadOnlyTrialPolicy.AllowedTarget> targets) {
        return new WorkflowReadOnlyTrialPolicy.TrialCommand(WorkflowReadOnlyTrialPolicy.VERSION, "wf-api", revision, hash,
                projectId, "orders", "42", targets, "查一下螺丝的库存", Map.of("orderNo", "A-1024"),
                System.currentTimeMillis() + 60_000);
    }

    private WorkflowReadOnlyTrialPolicy.TrialCommand command() { return command(41L, revision(), HASH, allowed()); }

    private void rejects(WorkflowReadOnlyTrialPolicy.TrialCommand command, String code) {
        assertEquals(code, assertThrows(RuntimeWorkflowReadOnlyTrialService.Conflict.class,
                () -> service.run(command)).code());
        verify(http, never()).withDraftScope(any(), any(), any());
        verify(capabilities, never()).withBusinessMethodDraftScope(any(), any(), any());
        verifyNoInteractions(debug);
    }

    @SuppressWarnings("unchecked")
    private void executeScopes(RuntimeWorkflowDebugService.DebugRunResult result) {
        when(http.withDraftScope(any(), any(), any())).thenAnswer(call -> ((Supplier<Object>) call.getArgument(2)).get());
        when(capabilities.withBusinessMethodDraftScope(any(), any(), any()))
                .thenAnswer(call -> ((Supplier<Object>) call.getArgument(2)).get());
        when(debug.runReadOnlyTrial(any(), any(), any(), any(), any())).thenReturn(result);
    }

    private static RuntimeWorkflowDebugService.DebugStepResult step(int index, String nodeId, String type, String status,
                                                                    long elapsed, String code, String message) {
        return new RuntimeWorkflowDebugService.DebugStepResult(index, nodeId, type, null, status, null, null, elapsed,
                Map.of(), Map.of(), null, Map.of(), Map.of(), "execute-node", null, null, null, null, null, code, message);
    }

    @Test void rereadsProjectRevisionHashAndTheExactPinnedNodeSetBeforeAnyScope() {
        rejects(command(42L, revision(), HASH, allowed()), "WORKFLOW_TRIAL_PROJECT_CHANGED");
        rejects(command(41L, "old", HASH, allowed()), "WORKFLOW_TRIAL_DRAFT_STALE");
        rejects(command(41L, revision(), "f".repeat(64), allowed()), "WORKFLOW_TRIAL_DRAFT_STALE");
        rejects(command(41L, revision(), HASH, allowed().subList(0, 1)), "WORKFLOW_TRIAL_TARGET_CHANGED");
        var wrongApi = List.of(allowed().get(0), WorkflowReadOnlyTrialPolicy.AllowedTarget.httpApi(
                new WorkflowReadOnlyTrialPolicy.Target("api", "查询库存", 2, "http-api:orders:dev:api", "HTTP_API"),
                "dev", "a".repeat(64), "b".repeat(64)));
        rejects(command(41L, revision(), HASH, wrongApi), "WORKFLOW_TRIAL_TARGET_CHANGED");
        var oldVersion = new WorkflowReadOnlyTrialPolicy.TrialCommand(1, "wf-api", revision(), HASH, 41L, "orders", "42",
                allowed(), null, Map.of(), System.currentTimeMillis() + 60_000);
        rejects(oldVersion, "WORKFLOW_TRIAL_REQUEST_INVALID");
        var tooLong = new WorkflowReadOnlyTrialPolicy.TrialCommand(2, "wf-api", revision(), HASH, 41L, "orders", "42",
                allowed(), null, Map.of(), System.currentTimeMillis() + WorkflowReadOnlyTrialPolicy.MAX_DURATION_MS + 60_000);
        rejects(tooLong, "WORKFLOW_TRIAL_REQUEST_INVALID");
    }

    @Test void sideEffectNodesAndCanonicalDriftAreRefusedBeforeAnyScope() {
        saved.setGraphSpecJson(GRAPH.replace("\"type\":\"ANSWER\"", "\"type\":\"PAGE_ACTION\""));
        rejects(command(41L, revision(), WorkflowReadOnlyTrialPolicy.graphSha256(saved.getGraphSpecJson()), allowed()),
                "WORKFLOW_TRIAL_GRAPH_UNSUPPORTED");
        saved.setGraphSpecJson(GRAPH);
        when(canonicalizer.canonicalizeGraphSpecJson(anyString()))
                .thenReturn(GRAPH.replace("orders:normalize", "orders:other"));
        rejects(command(), "WORKFLOW_TRIAL_GRAPH_UNSUPPORTED");
    }

    @Test void runtimeRechecksEveryMethodOwnerBeforeCreatingExecutionScopes() throws Exception {
        for (var mutation : Map.<String, Object>of("enabled", false, "sourceAvailability", "MISSING",
                "acceptedContractHash", "b".repeat(64), "sourceContractHash", "b".repeat(64), "credentialAvailable", false,
                "businessIdentityRequired", true, "sideEffect", "WRITE", "projectId", 99L, "assetType", "HTTP_API",
                "executionRevision", "c".repeat(64)).entrySet()) {
            com.fasterxml.jackson.databind.node.ObjectNode changed = json.valueToTree(methodOwner());
            changed.set(mutation.getKey(), json.valueToTree(mutation.getValue()));
            when(capabilities.getBusinessMethodExecutionContext("orders:normalize", "orders")).thenReturn(
                    json.treeToValue(changed, ConsoleCapabilityInvocationContracts.InvocationContext.class));
            assertThrows(RuntimeWorkflowReadOnlyTrialService.Conflict.class, () -> service.run(command()), mutation.getKey());
        }
        verify(capabilities, never()).withBusinessMethodDraftScope(any(), any(), any());
        verify(http, never()).withDraftScope(any(), any(), any());
        verifyNoInteractions(debug);
    }

    @Test void apiOwnerFactsMustMatchWhatControlApproved() {
        when(http.prepareDraft(any(), any(), anyString(), any())).thenReturn(new RuntimeWorkflowHttpApiService.DraftPin(
                "wf-api", revision(), "api", 1L, "http-api:orders:dev:api", 41L, "orders", "prod",
                "a".repeat(64), "b".repeat(64), 3L, null));
        rejects(command(), "HTTP_API_TRIAL_OWNER_CHANGED");
        when(http.prepareDraft(any(), any(), anyString(), any())).thenThrow(new IllegalArgumentException("HTTP_API_TRIAL_READ_ONLY_REQUIRED"));
        rejects(command(), "HTTP_API_TRIAL_READ_ONLY_REQUIRED");
    }

    @Test
    @SuppressWarnings("unchecked")
    void wholeDraftRunsInsidePinnedScopesAndReportsPerNodeStepsAndTheFailingNodeReason() {
        var state = Map.<String, Object>of("nodeOutput", Map.of("method", Map.of("id", 42, "name", "螺丝")),
                "var", Map.of("productName", "螺丝"));
        executeScopes(new RuntimeWorkflowDebugService.DebugRunResult("run-1", "trace-1", null, "WORKFLOW", false, "FAILED",
                null, "extract", List.of(), null, List.of(step(0, "input", "USER_INPUT", "SUCCESS", 1, null, null),
                step(1, "method", "TOOL", "SUCCESS", 120, null, null),
                step(2, "extract", "PARAMETER_EXTRACT", "ERROR", 800, "RUNTIME_PARAMETER_EXTRACT_REQUIRED_MISSING",
                        "required field is missing: productId")), state, "RUNTIME_PARAMETER_EXTRACT_REQUIRED_MISSING",
                "required field is missing: productId"));

        var result = service.run(command());

        var pins = ArgumentCaptor.forClass(Collection.class);
        verify(http).withDraftScope(pins.capture(), any(), any());
        assertEquals(List.of(apiPin), List.copyOf(pins.getValue()));
        var scope = ArgumentCaptor.forClass(WorkflowReadOnlyTrialPolicy.BusinessMethodTrialScope.class);
        verify(capabilities).withBusinessMethodDraftScope(scope.capture(), any(BooleanSupplier.class), any());
        assertEquals(Map.of("method", methodOwner()), scope.getValue().owners());
        assertEquals("orders", scope.getValue().projectCode());
        var identity = ArgumentCaptor.forClass(WorkflowExecutionIdentity.class);
        var audit = ArgumentCaptor.forClass(RuntimeWorkflowDebugService.TrialAudit.class);
        verify(debug).runReadOnlyTrial(any(), eq("查一下螺丝的库存"), eq(Map.of("orderNo", "A-1024")), identity.capture(), audit.capture());
        assertEquals(WorkflowExecutionIdentity.Source.STUDIO_PROJECT_TEST, identity.getValue().source());
        assertEquals(2, audit.getValue().targets().size());
        assertFalse(audit.getValue().targets().toString().contains("A-1024"), "audit never records input values");

        assertFalse(result.success());
        assertEquals(3, result.steps().size());
        assertEquals(Map.of("id", 42, "name", "螺丝"), result.steps().get(1).output());
        assertEquals(120L, result.steps().get(1).elapsedMs());
        assertEquals("ERROR", result.steps().get(2).status());
        assertEquals("extract", result.failedNodeId());
        assertEquals("提取产品", result.failedNodeName());
        assertEquals("RUNTIME_PARAMETER_EXTRACT_REQUIRED_MISSING", result.errorCode());
        assertEquals("required field is missing: productId", result.errorMessage());
        assertEquals(List.of("method", "api"), result.targets().stream().map(RuntimeWorkflowReadOnlyTrialService.TrialTarget::nodeId).toList());
    }

    @Test void publishedWorkflowWorkingCopyCanBeTrialledAndWrappedGatewayCodesAreExplained() {
        saved.setStatus("PUBLISHED");
        executeScopes(new RuntimeWorkflowDebugService.DebugRunResult("run-1", "trace-1", null, "WORKFLOW", false, "FAILED",
                null, "method", List.of(), null, List.of(step(0, "method", "TOOL", "ERROR", 5, "RUNTIME_GRAPH_TOOL_FAILED",
                "TOOL node execution failed: BUSINESS_METHOD_TRIAL_READ_ONLY_REQUIRED")), Map.of(), "RUNTIME_GRAPH_TOOL_FAILED",
                "TOOL node execution failed: BUSINESS_METHOD_TRIAL_READ_ONLY_REQUIRED"));
        var result = service.run(command());
        assertEquals("BUSINESS_METHOD_TRIAL_READ_ONLY_REQUIRED", result.errorCode());
        assertTrue(result.errorMessage().contains("不是只读操作"), result.errorMessage());
    }

    @Test void factsChangedDuringTheRunHideOutputsAndAnswer() {
        executeScopes(new RuntimeWorkflowDebugService.DebugRunResult("run-1", "trace-1", null, "WORKFLOW", true, "COMPLETED",
                "库存 12", "answer", List.of(), null, List.of(step(0, "method", "TOOL", "SUCCESS", 5, null, null)),
                Map.of("nodeOutput", Map.of("method", "secret-ish")), null, null));
        when(capabilities.getBusinessMethodExecutionContext("orders:normalize", "orders"))
                .thenReturn(methodOwner()).thenThrow(new IllegalStateException("gone"));
        var result = service.run(command());
        assertFalse(result.success());
        assertEquals("RESULT_STALE_AFTER_DISPATCH", result.status());
        assertNull(result.answer());
        assertNull(result.steps().get(0).output());
    }

    @Test void internalControllerRequiresVerifiedControlPlatformActorAndProjectBinding() {
        var mockService = mock(RuntimeWorkflowReadOnlyTrialService.class);
        var controller = new RuntimeWorkflowReadOnlyTrialInternalController(mockService);
        var command = command();
        for (var auth : List.of(new VerifiedInternalServiceAuth("RUNTIME", "PLATFORM_SESSION", "orders", "42"),
                new VerifiedInternalServiceAuth("CONTROL", "A2A_REMOTE_AGENT", "orders", "42"),
                new VerifiedInternalServiceAuth("CONTROL", "PLATFORM_SESSION", "other", "42"),
                new VerifiedInternalServiceAuth("CONTROL", "PLATFORM_SESSION", "orders", "other"))) {
            var request = new MockHttpServletRequest(); request.setAttribute(VerifiedInternalServiceAuth.REQUEST_ATTR, auth);
            assertEquals(401, controller.run(request, command).getStatusCode().value());
        }
        assertEquals(401, controller.run(new MockHttpServletRequest(), command).getStatusCode().value());
        verifyNoInteractions(mockService);
    }
}
