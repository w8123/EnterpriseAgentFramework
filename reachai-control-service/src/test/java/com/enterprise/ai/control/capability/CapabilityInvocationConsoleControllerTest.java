package com.enterprise.ai.control.capability;

import com.enterprise.ai.common.capability.ConsoleCapabilityInvocationContracts;
import com.enterprise.ai.control.governance.ControlToolAclDecisionService;
import com.enterprise.ai.control.identity.PlatformAuthenticatedSession;
import com.enterprise.ai.control.identity.PlatformConsoleRoutePolicy;
import com.enterprise.ai.control.identity.PlatformPermissionGrant;
import com.enterprise.ai.control.identity.PlatformPermissions;
import com.enterprise.ai.control.identity.PlatformPrincipal;
import com.enterprise.ai.control.identity.PlatformRequestAuthorization;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import java.time.LocalDateTime;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CapabilityInvocationConsoleControllerTest {

    @Test
    void platformSessionRoutesAreExplicitlyProtected() {
        assertEquals(PlatformConsoleRoutePolicy.CredentialDomain.PLATFORM_SESSION,
                PlatformConsoleRoutePolicy.credentialDomainFor("/api/business-methods/orders.lookup/invocations"));
        assertEquals(PlatformConsoleRoutePolicy.CredentialDomain.PLATFORM_SESSION,
                PlatformConsoleRoutePolicy.credentialDomainFor("/api/business-method-invocations/00000000-0000-0000-0000-000000000001"));
    }

    @Test
    void unconfirmedWriteNeverReachesRuntime() {
        Fixture fixture = fixture("WRITE", ControlToolAclDecisionService.DECISION_ALLOW);
        ResponseEntity<Object> response = fixture.controller.invoke(fixture.request, "orders.lookup", Map.of(
                "invocationId", "00000000-0000-0000-0000-000000000001",
                "expectedContractHash", "a".repeat(64), "input", Map.of("orderNo", "O-1"),
                "confirmedSideEffect", false));

        assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
        assertEquals("CONSOLE_CAPABILITY_CONFIRMATION_REQUIRED", ((Map<?, ?>) response.getBody()).get("code"));
        verify(fixture.runtime, never()).invoke(any(), anyString());
    }

    @Test
    void aclDenyFailsClosedBeforeRuntimeDispatch() {
        Fixture fixture = fixture("READ_ONLY", ControlToolAclDecisionService.DECISION_DENY_NO_MATCH);
        ResponseEntity<Object> response = fixture.controller.invoke(fixture.request, "orders.lookup", Map.of(
                "invocationId", "00000000-0000-0000-0000-000000000002",
                "expectedContractHash", "a".repeat(64), "input", Map.of("orderNo", "O-2"),
                "confirmedSideEffect", false));

        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
        assertEquals("CONSOLE_CAPABILITY_ACL_DENIED", ((Map<?, ?>) response.getBody()).get("code"));
        verify(fixture.runtime, never()).invoke(any(), anyString());
    }

    @Test
    void acceptedReadonlyContextBuildsAnAttestedRuntimeCommandWithoutBrowserIdentity() {
        Fixture fixture = fixture("READ_ONLY", ControlToolAclDecisionService.DECISION_ALLOW);
        when(fixture.runtime.invoke(any(), eq("42"))).thenAnswer(call -> ResponseEntity.ok(outcome(
                call.getArgument(0, ConsoleCapabilityInvocationContracts.InvocationCommand.class), "SUCCEEDED")));

        ResponseEntity<Object> response = fixture.controller.invoke(fixture.request, "orders.lookup", Map.of(
                "invocationId", "00000000-0000-0000-0000-000000000003",
                "expectedContractHash", "a".repeat(64), "input", Map.of("orderNo", "O-3", "password", "browser-secret"),
                "confirmedSideEffect", false));

        assertEquals(HttpStatus.OK, response.getStatusCode());
        ArgumentCaptor<ConsoleCapabilityInvocationContracts.InvocationCommand> command = ArgumentCaptor.forClass(
                ConsoleCapabilityInvocationContracts.InvocationCommand.class);
        verify(fixture.runtime).invoke(command.capture(), eq("42"));
        assertEquals("42", command.getValue().platformActorId());
        assertEquals("orders.lookup", command.getValue().qualifiedName());
        assertEquals(List.of("password"), command.getValue().sensitiveInputNames());
        assertTrue(command.getValue().input().containsKey("password"));
    }

    @Test
    void sourceTimeoutIsDefaultedAndCappedBeforeTheRuntimeCommand() {
        for (long declared : List.of(0L, 12_345L, 120_000L)) {
            Fixture fixture = fixture("READ_ONLY", ControlToolAclDecisionService.DECISION_ALLOW, declared);
            when(fixture.runtime.invoke(any(), eq("42"))).thenAnswer(call -> ResponseEntity.ok(outcome(
                    call.getArgument(0, ConsoleCapabilityInvocationContracts.InvocationCommand.class), "ACCEPTED")));
            fixture.controller.invoke(fixture.request, "orders.lookup", Map.of(
                    "invocationId", java.util.UUID.randomUUID().toString(), "expectedContractHash", "a".repeat(64),
                    "input", Map.of("orderNo", "O-timeout"), "confirmedSideEffect", false));
            ArgumentCaptor<ConsoleCapabilityInvocationContracts.InvocationCommand> command = ArgumentCaptor.forClass(
                    ConsoleCapabilityInvocationContracts.InvocationCommand.class);
            verify(fixture.runtime).invoke(command.capture(), eq("42"));
            long timeout = command.getValue().deadlineEpochMs() - System.currentTimeMillis();
            long expected = declared <= 0 ? 30_000L : Math.min(60_000L, declared);
            assertTrue(timeout <= expected && timeout >= expected - 1_000L,
                    "deadline must be derived from accepted source timeout");
        }
    }

    @Test
    void invalidRuntimePostResponseDoesNotLeakAndBecomesQueryOnlyUnknown() {
        Fixture fixture = fixture("READ_ONLY", ControlToolAclDecisionService.DECISION_ALLOW);
        when(fixture.runtime.invoke(any(), eq("42"))).thenReturn(ResponseEntity.ok(Map.of(
                "contractVersion", 1, "invocationId", "00000000-0000-0000-0000-000000000099",
                "projectId", 99L, "projectCode", "other", "qualifiedName", "other.method", "terminal", true,
                "message", "private other project result")));

        ResponseEntity<Object> response = fixture.controller.invoke(fixture.request, "orders.lookup", Map.of(
                "invocationId", "00000000-0000-0000-0000-000000000007", "expectedContractHash", "a".repeat(64),
                "input", Map.of(), "confirmedSideEffect", false));

        assertEquals(HttpStatus.ACCEPTED, response.getStatusCode());
        assertFalse(String.valueOf(response.getBody()).contains("private other project result"));
    }

    @Test
    void invalidRuntimeQueryCorrelationDoesNotLeakTheReturnedRecord() {
        Fixture fixture = fixture("READ_ONLY", ControlToolAclDecisionService.DECISION_ALLOW);
        when(fixture.runtime.get("00000000-0000-0000-0000-000000000008", "42")).thenReturn(ResponseEntity.ok(Map.of(
                "contractVersion", 1, "invocationId", "00000000-0000-0000-0000-000000000009",
                "projectId", 7L, "projectCode", "orders", "qualifiedName", "orders.lookup", "terminal", true,
                "message", "private mismatched result")));

        ResponseEntity<Object> response = fixture.controller.get(fixture.request,
                "00000000-0000-0000-0000-000000000008");

        assertEquals(HttpStatus.BAD_GATEWAY, response.getStatusCode());
        assertFalse(String.valueOf(response.getBody()).contains("private mismatched result"));
    }

    @Test
    void impossibleRuntimeLifecycleIsRejectedForBothPostAndStoredGet() {
        Fixture fixture = fixture("READ_ONLY", ControlToolAclDecisionService.DECISION_ALLOW);
        Map<String, Object> impossible = new LinkedHashMap<>();
        impossible.put("contractVersion", 1);
        impossible.put("invocationId", "00000000-0000-0000-0000-000000000010");
        impossible.put("runId", 1L);
        impossible.put("traceId", "trace-10");
        impossible.put("projectId", 7L);
        impossible.put("projectCode", "orders");
        impossible.put("qualifiedName", "orders.lookup");
        impossible.put("identityMode", "PROJECT_CREDENTIAL_NO_BUSINESS_IDENTITY");
        impossible.put("status", "UNKNOWN");
        impossible.put("dispatchStage", "CONFIRMED");
        impossible.put("terminal", false);
        impossible.put("message", "private impossible state");
        when(fixture.runtime.get("00000000-0000-0000-0000-000000000010", "42"))
                .thenReturn(ResponseEntity.ok(impossible));

        ResponseEntity<Object> response = fixture.controller.get(fixture.request,
                "00000000-0000-0000-0000-000000000010");

        assertEquals(HttpStatus.BAD_GATEWAY, response.getStatusCode());
        assertFalse(String.valueOf(response.getBody()).contains("private impossible state"));
    }

    @Test
    void rejectsLegacyAcceptedStageThatRuntimeNeverPersists() {
        Fixture fixture = fixture("READ_ONLY", ControlToolAclDecisionService.DECISION_ALLOW);
        Map<String, Object> impossible = new LinkedHashMap<>();
        impossible.put("contractVersion", 1);
        impossible.put("invocationId", "00000000-0000-0000-0000-000000000011");
        impossible.put("runId", 1L);
        impossible.put("traceId", "trace-11");
        impossible.put("projectId", 7L);
        impossible.put("projectCode", "orders");
        impossible.put("qualifiedName", "orders.lookup");
        impossible.put("identityMode", "PROJECT_CREDENTIAL_NO_BUSINESS_IDENTITY");
        impossible.put("status", "ACCEPTED");
        impossible.put("dispatchStage", "ACCEPTED");
        impossible.put("terminal", false);
        impossible.put("message", "private legacy stage");
        when(fixture.runtime.get("00000000-0000-0000-0000-000000000011", "42"))
                .thenReturn(ResponseEntity.ok(impossible));

        ResponseEntity<Object> response = fixture.controller.get(fixture.request,
                "00000000-0000-0000-0000-000000000011");

        assertEquals(HttpStatus.BAD_GATEWAY, response.getStatusCode());
        assertFalse(String.valueOf(response.getBody()).contains("private legacy stage"));
    }

    @Test
    void lostRuntimeResponseReturnsTheSameIdAsAQueryOnlyUnknownOutcome() {
        Fixture fixture = fixture("READ_ONLY", ControlToolAclDecisionService.DECISION_ALLOW);
        when(fixture.runtime.invoke(any(), eq("42"))).thenThrow(new IllegalStateException("private transport detail"));

        ResponseEntity<Object> response = fixture.controller.invoke(fixture.request, "orders.lookup", Map.of(
                "invocationId", "00000000-0000-0000-0000-000000000004",
                "expectedContractHash", "a".repeat(64), "input", Map.of("orderNo", "O-4"),
                "confirmedSideEffect", false));

        assertEquals(HttpStatus.ACCEPTED, response.getStatusCode());
        Map<?, ?> body = (Map<?, ?>) response.getBody();
        assertEquals("UNKNOWN", body.get("status"));
        assertEquals("00000000-0000-0000-0000-000000000004", body.get("invocationId"));
        assertEquals(true, body.get("queryable"));
        assertTrue(!String.valueOf(body).contains("private transport detail"));
    }

    private Fixture fixture(String sideEffect, String aclDecision) {
        return fixture(sideEffect, aclDecision, 30_000L);
    }

    private Fixture fixture(String sideEffect, String aclDecision, long timeoutMs) {
        CapabilityReviewGateway capability = mock(CapabilityReviewGateway.class);
        RuntimeConsoleCapabilityInvocationGateway runtime = mock(RuntimeConsoleCapabilityInvocationGateway.class);
        PlatformRequestAuthorization authorization = mock(PlatformRequestAuthorization.class);
        ControlToolAclDecisionService acl = mock(ControlToolAclDecisionService.class);
        MockHttpServletRequest request = new MockHttpServletRequest();
        PlatformAuthenticatedSession session = new PlatformAuthenticatedSession(new PlatformPrincipal(42L, "ops", "Ops"),
                "session", LocalDateTime.now().plusMinutes(5), List.of("OPERATOR"),
                List.of(PlatformPermissions.PLATFORM_READ, PlatformPermissions.CAPABILITY_INVOKE),
                List.of(new PlatformPermissionGrant(PlatformPermissions.PLATFORM_READ, "GLOBAL", "*"),
                        new PlatformPermissionGrant(PlatformPermissions.CAPABILITY_INVOKE, "GLOBAL", "*")));
        when(authorization.requirePermission(request, PlatformPermissions.PLATFORM_READ)).thenReturn(session);
        when(authorization.requirePermission(request, PlatformPermissions.CAPABILITY_INVOKE)).thenReturn(session);
        when(capability.getBusinessMethodInvocationContext("orders.lookup", "42")).thenReturn(ResponseEntity.ok(context(sideEffect, timeoutMs)));
        when(acl.decide(eq(session.roles()), eq(7L), eq("orders"), eq("TOOL"), eq("orders.lookup")))
                .thenReturn(aclDecision);
        return new Fixture(new CapabilityInvocationConsoleController(capability, runtime, authorization, acl,
                new ObjectMapper()), request, runtime);
    }

    private Map<String, Object> context(String sideEffect, long timeoutMs) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("contractVersion", 1); result.put("name", "orders.lookup");
        result.put("qualifiedName", "orders.lookup"); result.put("sourceQualifiedName", "orders.lookup");
        result.put("assetType", "BUSINESS_METHOD"); result.put("projectId", 7L); result.put("projectCode", "orders");
        result.put("currentContractHash", "a".repeat(64)); result.put("acceptedContractHash", "a".repeat(64));
        result.put("sourceContractHash", "a".repeat(64)); result.put("sourceAvailability", "READY");
        result.put("enabled", true); result.put("sideEffect", sideEffect);
        result.put("parameters", List.of(Map.of("name", "password", "type", "string", "required", false,
                "children", List.of(), "metadata", Map.of("sensitive", true))));
        result.put("requestBodyType", "json"); result.put("responseType", "json");
        result.put("targetDescription", "POST · 已接受项目契约"); result.put("targetInstanceStatus", "UNKNOWN");
        result.put("credentialAvailable", true); result.put("businessIdentityRequired", false); result.put("executable", true);
        result.put("timeoutMs", timeoutMs);
        return result;
    }

    private Map<String, Object> outcome(ConsoleCapabilityInvocationContracts.InvocationCommand command, String status) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("contractVersion", 1); result.put("invocationId", command.invocationId()); result.put("runId", 1L);
        result.put("traceId", "trace-1"); result.put("projectId", command.projectId());
        result.put("projectCode", command.projectCode()); result.put("qualifiedName", command.qualifiedName());
        result.put("identityMode", "PROJECT_CREDENTIAL_NO_BUSINESS_IDENTITY"); result.put("status", status);
        result.put("dispatchStage", switch (status) {
            case "ACCEPTED" -> "PERSISTED";
            case "DISPATCHING" -> "DISPATCHING";
            case "NOT_DISPATCHED" -> "NOT_DISPATCHED";
            case "UNKNOWN" -> "UNCONFIRMED";
            default -> "CONFIRMED";
        });
        result.put("terminal", !"ACCEPTED".equals(status) && !"DISPATCHING".equals(status));
        return result;
    }

    private record Fixture(CapabilityInvocationConsoleController controller,
                           HttpServletRequest request,
                           RuntimeConsoleCapabilityInvocationGateway runtime) { }
}
