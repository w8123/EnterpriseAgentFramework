package com.enterprise.ai.control.runtime;

import com.enterprise.ai.common.capability.HttpApiDraftTrialPolicy;
import com.enterprise.ai.common.capability.WorkflowReadOnlyTrialPolicy;
import com.enterprise.ai.control.capability.CapabilityReviewGateway;
import com.enterprise.ai.control.client.runtime.RuntimeProxyClient;
import com.enterprise.ai.control.governance.ControlToolAclDecisionService;
import com.enterprise.ai.control.identity.PlatformAuthenticatedSession;
import com.enterprise.ai.control.identity.PlatformPermissions;
import com.enterprise.ai.control.identity.PlatformPrincipal;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ControlWorkflowReadOnlyTrialControllerTest {
    private final ObjectMapper json = new ObjectMapper();
    private final RuntimeProxyClient runtime = mock(RuntimeProxyClient.class);
    private final CapabilityReviewGateway owner = mock(CapabilityReviewGateway.class);
    private final RuntimeManagementAccess access = mock(RuntimeManagementAccess.class);
    private final ControlToolAclDecisionService acl = mock(ControlToolAclDecisionService.class);
    private final ControlWorkflowReadOnlyTrialGateway gateway = mock(ControlWorkflowReadOnlyTrialGateway.class);
    private final PlatformPrincipal user = mock(PlatformPrincipal.class);
    private final PlatformAuthenticatedSession session = new PlatformAuthenticatedSession(user, "isolated", null,
            List.of("implementer"), List.of(), List.of());
    private ControlWorkflowReadOnlyTrialController controller;
    private Map<String, Object> summary;
    private Map<String, Object> contract;
    private static final String GRAPH = """
            {"nodes":[{"id":"api","type":"TOOL","ref":{"kind":"TOOL","qualifiedName":"http-api:orders:dev:api"},
              "config":{"httpApiAssetId":1,"inputMapping":{"pathParams.id":"params.id"}}},
              {"id":"variable","type":"VARIABLE_ASSIGN","config":{"assignments":{"state":"nodeOutput.api.state"}}}],
              "edges":[{"from":"api","to":"variable"}],"entryNodeId":"api","exitNodeIds":["variable"]}
            """;

    @BeforeEach void prepare() {
        when(user.getId()).thenReturn(42L);
        when(access.require(anyString())).thenReturn(session);
        when(runtime.workflowWorkingCopy("wf-api")).thenReturn(ResponseEntity.ok(Map.of(
                "workflowId", "wf-api", "projectId", 41L, "projectCode", "orders", "revision", "saved-r1",
                "status", "DRAFT", "graphSpecJson", GRAPH)));
        summary = new LinkedHashMap<>(Map.of("id", 1L, "projectId", 41L, "projectCode", "orders",
                "qualifiedName", "http-api:orders:dev:api", "environment", "dev", "httpMethod", "GET",
                "sourceConfirmed", true, "sourceStatus", "ACCEPTED", "acceptedContractHash", "a".repeat(64)));
        summary.put("candidateContractHash", "a".repeat(64)); summary.put("sourceSetRevision", "b".repeat(64));
        contract = new LinkedHashMap<>(Map.of("identity", Map.of("method", "GET", "routeTemplate", "/orders/{id}"),
                "sideEffect", "READ_ONLY", "parameters", List.of(Map.of("name", "id", "location", "PATH",
                        "required", true, "schema", Map.of("type", "string")))));
        contract.put("responses", List.of(Map.of("schema", Map.of("type", "object"),
                "contentTypes", List.of("application/json"))));
        contract.put("authentication", Map.of("state", "UNKNOWN"));
        when(owner.getHttpApi(1L, "42")).thenAnswer(call -> ResponseEntity.ok(Map.of("summary", summary,
                "acceptedContract", contract)));
        when(acl.decide(session.roles(), 41L, "orders", "TOOL", "http-api:orders:dev:api")).thenReturn("ALLOW");
        when(gateway.run(any())).thenReturn(ResponseEntity.ok(Map.of("success", true)));
        controller = new ControlWorkflowReadOnlyTrialController(runtime, owner, access, acl, gateway, json);
    }

    private Map<String, Object> input() {
        return new LinkedHashMap<>(Map.of("workflowId", "wf-api", "expectedRevision", "saved-r1",
                "inputParams", Map.of("id", "O-321")));
    }

    @Test void browserCannotSupplyTrustGraphPinsActorOrCredentials() {
        for (String name : List.of("graphSpecJson", "projectId", "platformActorId", "allowedTargets", "credentialRef", "identity")) {
            var body = input(); body.put(name, "forged");
            assertEquals(HttpStatus.BAD_REQUEST, controller.run(body).getStatusCode());
        }
        var nested = input(); nested.put("inputParams", Map.of("id", Map.of("secret", "forged")));
        assertEquals(HttpStatus.BAD_REQUEST, controller.run(nested).getStatusCode());
        verifyNoInteractions(runtime, owner, acl, gateway);
    }

    @Test void sessionPermissionAndProjectGrantDenialsNeverSign() {
        when(access.require(PlatformPermissions.WORKFLOW_DEBUG)).thenThrow(new ResponseStatusException(HttpStatus.UNAUTHORIZED));
        assertThrows(ResponseStatusException.class, () -> controller.run(input()));
        doReturn(session).when(access).require(PlatformPermissions.WORKFLOW_DEBUG);
        when(access.require(PlatformPermissions.CAPABILITY_INVOKE)).thenThrow(new ResponseStatusException(HttpStatus.FORBIDDEN));
        assertThrows(ResponseStatusException.class, () -> controller.run(input()));
        doReturn(session).when(access).require(PlatformPermissions.CAPABILITY_INVOKE);
        doThrow(new ResponseStatusException(HttpStatus.FORBIDDEN)).when(access)
                .requireProject(session, PlatformPermissions.WORKFLOW_DEBUG, 41L, "orders");
        assertThrows(ResponseStatusException.class, () -> controller.run(input()));
        verifyNoInteractions(owner, acl, gateway);
    }

    @Test void revisionOwnerSourceReadOnlyAndAclAreAllFailClosed() {
        var old = input(); old.put("expectedRevision", "old");
        assertEquals(HttpStatus.CONFLICT, controller.run(old).getStatusCode());
        summary.put("projectCode", "other");
        assertEquals(HttpStatus.CONFLICT, controller.run(input()).getStatusCode());
        summary.put("projectCode", "orders"); summary.put("sourceConfirmed", false);
        assertEquals(HttpStatus.CONFLICT, controller.run(input()).getStatusCode());
        summary.put("sourceConfirmed", true); summary.put("candidateContractHash", "c".repeat(64));
        assertEquals(HttpStatus.CONFLICT, controller.run(input()).getStatusCode());
        summary.put("candidateContractHash", "a".repeat(64));
        for (String sideEffect : List.of("WRITE", "UNKNOWN", "")) {
            contract.put("sideEffect", sideEffect);
            assertEquals(HttpStatus.CONFLICT, controller.run(input()).getStatusCode());
        }
        contract.put("sideEffect", "READ_ONLY");
        when(acl.decide(anyList(), anyLong(), anyString(), anyString(), anyString())).thenReturn("DENY_NO_MATCH");
        assertEquals(HttpStatus.FORBIDDEN, controller.run(input()).getStatusCode());
        verifyNoInteractions(gateway);
    }

    @Test void buildsOneAttestedCommandOnlyFromSavedOwnerAndRealSession() {
        assertEquals(HttpStatus.OK, controller.run(input()).getStatusCode());
        var captor = ArgumentCaptor.forClass(WorkflowReadOnlyTrialPolicy.TrialCommand.class);
        verify(gateway).run(captor.capture());
        var command = captor.getValue();
        assertEquals("42", command.platformActorId()); assertEquals(41L, command.projectId());
        assertEquals("saved-r1", command.expectedRevision());
        assertEquals(HttpApiDraftTrialPolicy.graphSha256(GRAPH), command.graphSha256());
        assertEquals(Map.of("id", "O-321"), command.inputParams());
        assertEquals(List.of(new WorkflowReadOnlyTrialPolicy.AllowedTarget("api", 1L, "http-api:orders:dev:api",
                "dev", "a".repeat(64), "b".repeat(64))), command.allowedTargets());
    }

    private Map<String, Object> prepareMethod() {
        String graph = """
                {"nodes":[{"id":"method","type":"TOOL","ref":{"kind":"TOOL","qualifiedName":"orders:normalize"},
                "config":{"inputMapping":{"orderNo":"params.orderNo"}}},
                {"id":"variable","type":"VARIABLE_ASSIGN","config":{"assignments":{"normalized":"nodeOutput.method.data"}}}],
                "edges":[{"from":"method","to":"variable"}],"entryNodeId":"method","exitNodeIds":["variable"]}
                """;
        when(runtime.workflowWorkingCopy("wf-api")).thenReturn(ResponseEntity.ok(Map.of("workflowId", "wf-api",
                "projectId", 41L, "projectCode", "orders", "revision", "saved-r1", "status", "DRAFT", "graphSpecJson", graph)));
        Map<String, Object> method = new LinkedHashMap<>();
        method.put("contractVersion", 1); method.put("name", "orders_normalize"); method.put("qualifiedName", "orders:normalize");
        method.put("sourceQualifiedName", "orders:normalize"); method.put("assetType", "BUSINESS_METHOD");
        method.put("projectId", 41L); method.put("projectCode", "orders"); method.put("enabled", true);
        method.put("sourceAvailability", "READY"); method.put("currentContractHash", "a".repeat(64));
        method.put("acceptedContractHash", "a".repeat(64)); method.put("sourceContractHash", "a".repeat(64));
        method.put("sideEffect", "READ_ONLY"); method.put("credentialAvailable", true);
        method.put("executionRevision", "c".repeat(64));
        method.put("businessIdentityRequired", false); method.put("executable", true); method.put("responseType", "String");
        method.put("parameters", List.of(Map.of("name", "orderNo", "type", "String", "required", true, "children", List.of(), "metadata", Map.of())));
        when(owner.getBusinessMethodInvocationContext("orders:normalize", "42")).thenAnswer(call -> ResponseEntity.ok(method));
        when(acl.decide(session.roles(), 41L, "orders", "TOOL", "orders:normalize")).thenReturn("ALLOW");
        return method;
    }
    private Map<String, Object> methodInput() {
        return Map.of("workflowId", "wf-api", "expectedRevision", "saved-r1", "inputParams", Map.of("orderNo", "A-1024"));
    }
    @Test void methodCommandUsesOwnerHashesNameProjectAndExactAcl() {
        prepareMethod(); assertEquals(HttpStatus.OK, controller.run(methodInput()).getStatusCode());
        var command = ArgumentCaptor.forClass(WorkflowReadOnlyTrialPolicy.TrialCommand.class); verify(gateway).run(command.capture());
        var target = command.getValue().allowedTargets().get(0);
        assertEquals("BUSINESS_METHOD", target.assetType()); assertEquals("orders_normalize", target.methodName());
        assertEquals("a".repeat(64), target.acceptedContractHash()); assertEquals("a".repeat(64), target.sourceContractHash());
        assertEquals("c".repeat(64), target.executionRevision());
        assertNull(target.environment()); assertNull(target.sourceSetRevision());
        verify(acl).decide(session.roles(), 41L, "orders", "TOOL", "orders:normalize");
    }
    @Test void methodOwnerDriftAndMissingInputCannotSignAnExecutionCommand() {
        Map<String, Object> method = prepareMethod();
        for (var change : Map.<String, Object>of("enabled", false, "sourceAvailability", "DRIFT", "sideEffect", "UNKNOWN",
                "credentialAvailable", false, "businessIdentityRequired", true, "sourceContractHash", "b".repeat(64),
                "acceptedContractHash", "b".repeat(64), "projectId", 99L, "assetType", "UNCLASSIFIED", "executionRevision", "").entrySet()) {
            Object previous = method.put(change.getKey(), change.getValue());
            assertEquals(HttpStatus.CONFLICT, controller.run(methodInput()).getStatusCode(), change.getKey());
            method.put(change.getKey(), previous);
        }
        assertEquals(HttpStatus.CONFLICT, controller.run(input()).getStatusCode());
        when(acl.decide(session.roles(), 41L, "orders", "TOOL", "orders:normalize")).thenReturn("DENY_NO_MATCH");
        assertEquals(HttpStatus.FORBIDDEN, controller.run(methodInput()).getStatusCode()); verifyNoInteractions(gateway);
    }
}
