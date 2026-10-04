package com.enterprise.ai.runtime.workflow;

import com.enterprise.ai.common.capability.HttpApiDraftTrialPolicy;
import com.enterprise.ai.common.capability.WorkflowReadOnlyTrialPolicy;
import com.enterprise.ai.common.capability.ConsoleCapabilityInvocationContracts;
import com.enterprise.ai.runtime.client.capability.RuntimeCapabilityCatalogClient;
import com.enterprise.ai.runtime.internalauth.VerifiedInternalServiceAuth;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RuntimeWorkflowReadOnlyTrialServiceTest {
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private final RuntimeWorkflowDefinitionService definitions = mock(RuntimeWorkflowDefinitionService.class);
    private final RuntimeWorkflowHttpApiService http = mock(RuntimeWorkflowHttpApiService.class);
    private final RuntimeWorkflowDebugService debug = mock(RuntimeWorkflowDebugService.class);
    private final RuntimeCapabilityCatalogClient capabilities = mock(RuntimeCapabilityCatalogClient.class);
    private final RuntimeWorkflowReadOnlyTrialService service = new RuntimeWorkflowReadOnlyTrialService(definitions, http,
            capabilities, debug, json);
    private static final String GRAPH = """
            {"nodes":[{"id":"api","type":"TOOL","ref":{"kind":"TOOL","qualifiedName":"http-api:orders:dev:api"},
              "config":{"httpApiAssetId":1,"inputMapping":{"pathParams.id":"params.id"}}},
              {"id":"variable","type":"VARIABLE_ASSIGN","config":{"assignments":{"state":"nodeOutput.api.state"}}}],
              "edges":[{"from":"api","to":"variable"}],"entryNodeId":"api","exitNodeIds":["variable"]}
            """;
    private RuntimeWorkflowDefinitionEntity saved() {
        var saved = new RuntimeWorkflowDefinitionEntity();
        saved.setId("wf-api"); saved.setProjectId(41L); saved.setProjectCode("orders"); saved.setStatus("DRAFT");
        saved.setUpdatedAt(LocalDateTime.parse("2026-09-30T10:00:00")); saved.setGraphSpecJson(GRAPH);
        when(definitions.findById("wf-api")).thenReturn(Optional.of(saved));
        return saved;
    }
    private WorkflowReadOnlyTrialPolicy.TrialCommand command(long projectId, String revision, String hash, long apiId) {
        return new WorkflowReadOnlyTrialPolicy.TrialCommand(1, "wf-api", revision, hash, projectId, "orders", "42",
                List.of(new WorkflowReadOnlyTrialPolicy.AllowedTarget("api", apiId, "http-api:orders:dev:api", "dev",
                        "a".repeat(64), "b".repeat(64))), Map.of("id", "O-321"), System.currentTimeMillis() + 40_000);
    }
    private void rejects(WorkflowReadOnlyTrialPolicy.TrialCommand command, String code) {
        assertEquals(code, assertThrows(RuntimeWorkflowReadOnlyTrialService.Conflict.class,
                () -> service.run(command)).code());
        verifyNoInteractions(http, debug);
    }
    @Test void rereadsProjectRevisionHashStatusAndExactTargetBeforePreparingHttp() {
        var saved = saved(); String revision = saved.getUpdatedAt().toString(); String hash = HttpApiDraftTrialPolicy.graphSha256(GRAPH);
        rejects(command(42L, revision, hash, 1L), "HTTP_API_TRIAL_PROJECT_CHANGED");
        rejects(command(41L, "old", hash, 1L), "HTTP_API_TRIAL_DRAFT_STALE");
        rejects(command(41L, revision, "f".repeat(64), 1L), "HTTP_API_TRIAL_DRAFT_STALE");
        rejects(command(41L, revision, hash, 2L), "HTTP_API_TRIAL_TARGET_CHANGED");
        saved.setStatus("PUBLISHED");
        rejects(command(41L, revision, hash, 1L), "HTTP_API_TRIAL_DRAFT_REQUIRED");
    }
    @Test void internalControllerRequiresVerifiedControlPlatformActorAndProjectBinding() {
        var mockService = mock(RuntimeWorkflowReadOnlyTrialService.class);
        var controller = new RuntimeWorkflowReadOnlyTrialInternalController(mockService);
        var command = command(41L, "saved-r1", "a".repeat(64), 1L);
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

    private static final String METHOD_GRAPH = """
            {"nodes":[{"id":"method","type":"TOOL","ref":{"kind":"TOOL","qualifiedName":"orders:normalize"},
            "config":{"inputMapping":{"orderNo":"params.orderNo"}}},
            {"id":"variable","type":"VARIABLE_ASSIGN","config":{"assignments":{"normalized":"nodeOutput.method.data"}}}],
            "edges":[{"from":"method","to":"variable"}],"entryNodeId":"method","exitNodeIds":["variable"]}
            """;
    private ConsoleCapabilityInvocationContracts.InvocationContext methodOwner() {
        return new ConsoleCapabilityInvocationContracts.InvocationContext(1, "orders_normalize", "orders:normalize",
                "orders:normalize", "BUSINESS_METHOD", 41L, "orders", "a".repeat(64), "a".repeat(64), "a".repeat(64),
                "READY", true, "READ_ONLY", List.of(new ConsoleCapabilityInvocationContracts.Parameter("orderNo", "String",
                null, true, "BODY", List.of(), Map.of())), null, "String", null, null, "UNKNOWN", true, false, true, null, null, 30_000);
    }
    private WorkflowReadOnlyTrialPolicy.TrialCommand methodCommand(RuntimeWorkflowDefinitionEntity saved, Map<String, Object> input) {
        return new WorkflowReadOnlyTrialPolicy.TrialCommand(1, "wf-api", saved.getUpdatedAt().toString(),
                WorkflowReadOnlyTrialPolicy.graphSha256(saved.getGraphSpecJson()), 41L, "orders", "42",
                List.of(WorkflowReadOnlyTrialPolicy.AllowedTarget.businessMethod(
                        WorkflowReadOnlyTrialPolicy.target(json, METHOD_GRAPH), methodOwner())), input, System.currentTimeMillis() + 40_000);
    }
    @Test void runtimeRechecksMethodOwnerTruthBeforeCreatingExecutionScope() throws Exception {
        var saved = saved(); saved.setGraphSpecJson(METHOD_GRAPH);
        for (var mutation : Map.<String, Object>of("enabled", false, "sourceAvailability", "MISSING",
                "acceptedContractHash", "b".repeat(64), "sourceContractHash", "b".repeat(64), "credentialAvailable", false,
                "businessIdentityRequired", true, "sideEffect", "WRITE", "projectId", 99L, "assetType", "HTTP_API").entrySet()) {
            com.fasterxml.jackson.databind.node.ObjectNode changed = json.valueToTree(methodOwner());
            changed.set(mutation.getKey(), json.valueToTree(mutation.getValue()));
            when(capabilities.getBusinessMethodExecutionContext("orders:normalize", "orders")).thenReturn(
                    json.treeToValue(changed, ConsoleCapabilityInvocationContracts.InvocationContext.class));
            assertThrows(RuntimeWorkflowReadOnlyTrialService.Conflict.class,
                    () -> service.run(methodCommand(saved, Map.of("orderNo", "A-1024"))), mutation.getKey());
        }
        verify(capabilities, never()).withBusinessMethodDraftScope(any(), any(), any());
        verifyNoInteractions(http, debug);
    }
    @Test void methodRequiredTypeMappingAndUnverifiedReturnFailBeforeExecution() {
        var saved = saved(); saved.setGraphSpecJson(METHOD_GRAPH);
        when(capabilities.getBusinessMethodExecutionContext("orders:normalize", "orders")).thenReturn(methodOwner());
        for (Map<String, Object> input : List.of(Map.<String, Object>of(), Map.<String, Object>of("orderNo", 7))) {
            assertThrows(RuntimeWorkflowReadOnlyTrialService.Conflict.class, () -> service.run(methodCommand(saved, input)));
        }
        saved.setGraphSpecJson(METHOD_GRAPH.replace("\"orderNo\":\"params.orderNo\"", "\"unknown\":\"params.orderNo\""));
        assertThrows(RuntimeWorkflowReadOnlyTrialService.Conflict.class, () -> service.run(methodCommand(saved, Map.of("orderNo", "A"))));
        saved.setGraphSpecJson(METHOD_GRAPH.replace("nodeOutput.method.data", "nodeOutput.method.data.unverified"));
        assertThrows(IllegalArgumentException.class, () -> service.run(methodCommand(saved, Map.of("orderNo", "A"))));
        verify(capabilities, never()).withBusinessMethodDraftScope(any(), any(), any()); verifyNoInteractions(http, debug);
    }
}
