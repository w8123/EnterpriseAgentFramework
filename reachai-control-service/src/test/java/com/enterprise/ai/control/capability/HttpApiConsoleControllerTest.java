package com.enterprise.ai.control.capability;

import com.enterprise.ai.common.capability.HttpApiConsoleContracts;
import com.enterprise.ai.control.governance.ControlToolAclDecisionService;
import com.enterprise.ai.control.identity.PlatformAuthenticatedSession;
import com.enterprise.ai.control.identity.PlatformConsoleRoutePolicy;
import com.enterprise.ai.control.identity.PlatformPermissions;
import com.enterprise.ai.control.identity.PlatformPrincipal;
import com.enterprise.ai.control.identity.PlatformRequestAuthorization;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Public API requests carry no target, credential, role or actor authority. */
class HttpApiConsoleControllerTest {
    private static final String HASH = "a".repeat(64);
    private static final String SOURCE = "b".repeat(64);
    private static final String REF = "orders.dev.GET./orders/{orderId}";

    @Test
    void apiRoutesRequirePlatformSessionDomain() {
        assertEquals(PlatformConsoleRoutePolicy.CredentialDomain.PLATFORM_SESSION,
                PlatformConsoleRoutePolicy.credentialDomainFor("/api/apis/9/invocations"));
        assertEquals(PlatformConsoleRoutePolicy.CredentialDomain.PLATFORM_SESSION,
                PlatformConsoleRoutePolicy.credentialDomainFor("/api/api-invocations/" + UUID.randomUUID()));
    }

    @Test
    void publicInvocationRejectsCallerSuppliedUrlHeaderOrActorBeforeRuntime() {
        Fixture fixture = fixture(ControlToolAclDecisionService.DECISION_ALLOW, HASH, true);
        Map<String, Object> body = new LinkedHashMap<>(input());
        body.put("url", "http://attacker.invalid");
        assertEquals(HttpStatus.BAD_REQUEST, fixture.controller.invoke(fixture.request, 9L, body).getStatusCode());
        body.remove("url"); body.put("headers", Map.of("X-API-Key", "secret"));
        assertEquals(HttpStatus.BAD_REQUEST, fixture.controller.invoke(fixture.request, 9L, body).getStatusCode());
        body.remove("headers"); body.put("platformActorId", "other-user");
        assertEquals(HttpStatus.BAD_REQUEST, fixture.controller.invoke(fixture.request, 9L, body).getStatusCode());
        verifyNoInteractions(fixture.runtime);
    }

    @Test
    void noExplicitAclAllowAndStaleContractNeverDispatch() {
        for (String denied : List.of(ControlToolAclDecisionService.DECISION_DENY_NO_MATCH,
                ControlToolAclDecisionService.DECISION_DENY_EXPLICIT)) {
            Fixture fixture = fixture(denied, HASH, true);
            ResponseEntity<Object> response = fixture.controller.invoke(fixture.request, 9L, input());
            assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
            verifyNoInteractions(fixture.runtime);
        }
        Fixture stale = fixture(ControlToolAclDecisionService.DECISION_ALLOW, "c".repeat(64), true);
        assertEquals(HttpStatus.CONFLICT, stale.controller.invoke(stale.request, 9L, input()).getStatusCode());
        verifyNoInteractions(stale.runtime);
        Fixture unconfirmed = fixture(ControlToolAclDecisionService.DECISION_ALLOW, HASH, false);
        assertEquals(HttpStatus.CONFLICT, unconfirmed.controller.invoke(unconfirmed.request, 9L, input()).getStatusCode());
        verifyNoInteractions(unconfirmed.runtime);
    }

    @Test
    void projectGrantDenialStopsBeforeRuntime() {
        Fixture fixture = fixture(ControlToolAclDecisionService.DECISION_ALLOW, HASH, true);
        doThrow(new ResponseStatusException(HttpStatus.FORBIDDEN)).when(fixture.authorization)
                .requireResourcePermission(fixture.request, PlatformPermissions.CAPABILITY_INVOKE,
                        "PROJECT", null, "orders");
        assertThrows(ResponseStatusException.class, () -> fixture.controller.invoke(fixture.request, 9L, input()));
        verifyNoInteractions(fixture.runtime);
    }

    @Test
    void currentAuthorizedApiBuildsOwnerBoundCommand() {
        Fixture fixture = fixture(ControlToolAclDecisionService.DECISION_ALLOW, HASH, true);
        when(fixture.runtime.invoke(any(), eq("42"))).thenAnswer(call -> {
            HttpApiConsoleContracts.InvocationCommand command = call.getArgument(0);
            return ResponseEntity.ok(Map.of("contractVersion", 1, "invocationId", command.invocationId(),
                    "targetType", "HTTP_API", "qualifiedName", command.qualifiedName(),
                    "projectId", command.projectId(), "projectCode", command.projectCode(),
                    "environment", command.environment(), "expectedContractHash", command.expectedContractHash(),
                    "sourceSetRevision", command.expectedSourceSetRevision(),
                    "connectionRevision", command.connectionRevision()));
        });
        ResponseEntity<Object> response = fixture.controller.invoke(fixture.request, 9L, input());

        assertEquals(HttpStatus.OK, response.getStatusCode());
        ArgumentCaptor<HttpApiConsoleContracts.InvocationCommand> command = ArgumentCaptor.forClass(
                HttpApiConsoleContracts.InvocationCommand.class);
        verify(fixture.runtime).invoke(command.capture(), eq("42"));
        assertEquals(9L, command.getValue().apiId());
        assertEquals(7L, command.getValue().projectId());
        assertEquals("orders", command.getValue().projectCode());
        assertEquals("dev", command.getValue().environment());
        assertEquals(REF, command.getValue().qualifiedName());
        assertEquals("42", command.getValue().platformActorId());
        assertEquals(SOURCE, command.getValue().expectedSourceSetRevision());
        assertEquals(Map.of("orderId", "O-1"), command.getValue().pathParams());
    }

    @Test
    void mismatchedRuntimePostResponseBecomesQueryOnlyWithoutLeakingIt() {
        Fixture fixture = fixture(ControlToolAclDecisionService.DECISION_ALLOW, HASH, true);
        when(fixture.runtime.invoke(any(), eq("42"))).thenReturn(ResponseEntity.ok(Map.of(
                "contractVersion", 1, "targetType", "HTTP_API", "invocationId", UUID.randomUUID().toString(),
                "projectId", 999L, "projectCode", "other", "qualifiedName", "other-api",
                "result", "private other project result")));
        ResponseEntity<Object> response = fixture.controller.invoke(fixture.request, 9L, input());

        assertEquals(HttpStatus.ACCEPTED, response.getStatusCode());
        assertFalse(String.valueOf(response.getBody()).contains("private other project result"));
    }

    @Test
    void writeNeedsCurrentSourceAndExplicitConfirmationThenBindsBodyWithoutCallerAuthority() {
        Fixture fixture = fixture(ControlToolAclDecisionService.DECISION_ALLOW, HASH, true);
        @SuppressWarnings("unchecked")
        Map<String, Object> summary = new LinkedHashMap<>((Map<String, Object>) ((Map<?, ?>) fixture.capability.getHttpApi(9L, "42").getBody()).get("summary"));
        when(fixture.capability.getHttpApi(9L, "42")).thenReturn(ResponseEntity.ok(Map.of("summary", summary,
                "acceptedContract", Map.of("sideEffect", "WRITE"))));
        var body = new LinkedHashMap<>(input()); body.put("body", Map.of("note", "one"));
        assertEquals(HttpStatus.CONFLICT, fixture.controller.invoke(fixture.request, 9L, body).getStatusCode());
        body.put("confirmedSideEffect", true);
        assertEquals(HttpStatus.CONFLICT, fixture.controller.invoke(fixture.request, 9L, body).getStatusCode());
        body.put("expectedSourceSetRevision", "c".repeat(64));
        assertEquals(HttpStatus.CONFLICT, fixture.controller.invoke(fixture.request, 9L, body).getStatusCode());
        verifyNoInteractions(fixture.runtime);
        body.put("expectedSourceSetRevision", SOURCE); body.put("expectedCredentialRevision", "d".repeat(64));
        when(fixture.runtime.invoke(any(), eq("42"))).thenReturn(ResponseEntity.accepted().build());
        fixture.controller.invoke(fixture.request, 9L, body);
        var captured = ArgumentCaptor.forClass(HttpApiConsoleContracts.InvocationCommand.class);
        verify(fixture.runtime).invoke(captured.capture(), eq("42"));
        assertEquals(Map.of("note", "one"), captured.getValue().body()); assertTrue(captured.getValue().confirmedSideEffect());
        assertEquals(SOURCE, captured.getValue().expectedSourceSetRevision()); assertEquals("d".repeat(64), captured.getValue().expectedCredentialRevision());
        assertEquals("42", captured.getValue().platformActorId()); assertEquals("orders", captured.getValue().projectCode());
    }

    @Test
    void catalogSeparatesOwnerSourceFromActorScopedRuntimeHistory() {
        Fixture fixture = fixture(ControlToolAclDecisionService.DECISION_ALLOW, HASH, true);
        Map<String, Object> row = Map.of("id", 9L, "projectId", 7L, "projectCode", "orders",
                "qualifiedName", REF, "sourceStatus", "ACCEPTED");
        when(fixture.capability.listHttpApis(7L, null, null, null, null, 1, 20, "42"))
                .thenReturn(ResponseEntity.ok(Map.of("records", List.of(row), "total", 1)));
        when(fixture.runtime.catalogStates(any(), eq("42"))).thenReturn(ResponseEntity.ok(List.of(
                new HttpApiConsoleContracts.CatalogState(REF, "SAVED", "HTTP_FAILED", 401,
                        "2026-09-23T12:00:00"))));

        ResponseEntity<Object> response = fixture.controller.list(fixture.request, 7L,
                null, null, null, null, 1, 20);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        @SuppressWarnings("unchecked")
        Map<String, Object> first = (Map<String, Object>) ((List<?>) ((Map<?, ?>) response.getBody())
                .get("records")).get(0);
        assertEquals("ACCEPTED", first.get("sourceStatus"));
        assertEquals("SAVED", first.get("connectionStatus"));
        assertEquals("HTTP_FAILED", first.get("latestInvocationStatus"));
        assertEquals(401, first.get("latestHttpStatus"));
        ArgumentCaptor<HttpApiConsoleContracts.CatalogStatesRequest> command = ArgumentCaptor.forClass(
                HttpApiConsoleContracts.CatalogStatesRequest.class);
        verify(fixture.runtime).catalogStates(command.capture(), eq("42"));
        assertEquals(7L, command.getValue().projectId());
        assertEquals(List.of(REF), command.getValue().qualifiedNames());
    }

    @Test
    void catalogRejectsAnOwnerPageContainingAnotherProjectBeforeRuntime() {
        Fixture fixture = fixture(ControlToolAclDecisionService.DECISION_ALLOW, HASH, true);
        when(fixture.capability.listHttpApis(7L, null, null, null, null, 1, 20, "42"))
                .thenReturn(ResponseEntity.ok(Map.of("records", List.of(Map.of("id", 9L,
                        "projectId", 7L, "projectCode", "other", "qualifiedName", REF)), "total", 1)));

        assertEquals(HttpStatus.BAD_GATEWAY, fixture.controller.list(fixture.request, 7L,
                null, null, null, null, 1, 20).getStatusCode());
        verifyNoInteractions(fixture.runtime);
    }

    @Test
    void originalInvocationReadbackRechecksActorProjectAndApiAcl() {
        Fixture fixture = fixture(ControlToolAclDecisionService.DECISION_DENY_NO_MATCH, HASH, true);
        String id = UUID.randomUUID().toString();
        when(fixture.runtime.getInvocation(id, "orders", "42")).thenReturn(ResponseEntity.ok(Map.of(
                "contractVersion", 1, "invocationId", id, "targetType", "HTTP_API",
                "projectId", 7L, "projectCode", "orders", "qualifiedName", REF,
                "environment", "dev", "status", "SUCCEEDED")));

        assertEquals(HttpStatus.FORBIDDEN, fixture.controller.getInvocation(fixture.request,
                id, "orders").getStatusCode());
        verify(fixture.authorization, times(2)).requireResourcePermission(fixture.request,
                PlatformPermissions.CAPABILITY_INVOKE, "PROJECT", null, "orders");
        verify(fixture.runtime).getInvocation(id, "orders", "42");
    }

    private Map<String, Object> input() {
        return Map.of("invocationId", UUID.randomUUID().toString(), "expectedContractHash", HASH,
                "connectionRevision", 1L, "pathParams", Map.of("orderId", "O-1"),
                "queryParams", Map.of("expanded", true));
    }

    private Fixture fixture(String decision, String candidateHash, boolean confirmed) {
        CapabilityReviewGateway capability = mock(CapabilityReviewGateway.class);
        RuntimeHttpApiConsoleGateway runtime = mock(RuntimeHttpApiConsoleGateway.class);
        PlatformRequestAuthorization authorization = mock(PlatformRequestAuthorization.class);
        ControlToolAclDecisionService acl = mock(ControlToolAclDecisionService.class);
        MockHttpServletRequest request = new MockHttpServletRequest();
        PlatformAuthenticatedSession session = new PlatformAuthenticatedSession(
                new PlatformPrincipal(42L, "ops", "Ops"), "session", LocalDateTime.now().plusMinutes(5),
                List.of("OPERATOR"), List.of(PlatformPermissions.PLATFORM_READ,
                PlatformPermissions.PLATFORM_WRITE, PlatformPermissions.CAPABILITY_INVOKE), List.of());
        when(authorization.requirePermission(eq(request), anyString())).thenReturn(session);
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("id", 9L); summary.put("projectId", 7L); summary.put("projectCode", "orders");
        summary.put("qualifiedName", REF); summary.put("environment", "dev");
        summary.put("sourceConfirmed", confirmed); summary.put("sourceStatus", "ACCEPTED");
        summary.put("candidateContractHash", candidateHash); summary.put("acceptedContractHash", HASH);
        summary.put("sourceSetRevision", SOURCE);
        when(capability.getHttpApi(9L, "42")).thenReturn(ResponseEntity.ok(Map.of("summary", summary)));
        when(capability.getProjectById(7L, "42"))
                .thenReturn(ResponseEntity.ok(Map.of("projectId", 7L, "projectCode", "orders")));
        when(acl.decide(eq(session.roles()), eq(7L), eq("orders"), eq("TOOL"), eq(REF))).thenReturn(decision);
        return new Fixture(new HttpApiConsoleController(capability, runtime, authorization, acl, new ObjectMapper()),
                request, capability, runtime, authorization);
    }

    private record Fixture(HttpApiConsoleController controller, MockHttpServletRequest request,
                           CapabilityReviewGateway capability, RuntimeHttpApiConsoleGateway runtime,
                           PlatformRequestAuthorization authorization) { }
}
