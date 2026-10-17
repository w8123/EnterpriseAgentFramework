package com.enterprise.ai.control.capability;

import com.enterprise.ai.common.capability.HttpApiConsoleContracts;
import com.enterprise.ai.control.governance.ControlToolAclDecisionService;
import com.enterprise.ai.control.identity.PlatformAuthenticatedSession;
import com.enterprise.ai.control.identity.PlatformConsoleRoutePolicy;
import com.enterprise.ai.control.identity.PlatformPermissions;
import com.enterprise.ai.control.identity.PlatformPermissionGrant;
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
    @Test
    void publicJsonWirePreservesRootArraysAndRejectsMalformedOrUnboundedBodiesBeforeRuntime() throws Exception {
        var fixture = fixture(ControlToolAclDecisionService.DECISION_ALLOW, HASH, true);
        var session = fixture.authorization.requirePermission(fixture.request, PlatformPermissions.PLATFORM_READ);
        when(fixture.authorization.requirePermission(any(), anyString())).thenReturn(session);
        when(fixture.runtime.invoke(any(), eq("42"))).thenReturn(ResponseEntity.accepted().build());
        var mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(fixture.controller).build();
        var json = new ObjectMapper();
        var wire = new LinkedHashMap<>(input());
        wire.put("body", List.of(Map.of("profile", Map.of("roles", List.of("reader", "writer")))));
        assertEquals(202, mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .post("/api/apis/9/invocations").contentType("application/json").content(json.writeValueAsBytes(wire)))
                .andReturn().getResponse().getStatus());
        var command = ArgumentCaptor.forClass(HttpApiConsoleContracts.InvocationCommand.class);
        verify(fixture.runtime).invoke(command.capture(), eq("42"));
        assertEquals(wire.get("body"), command.getValue().body());
        clearInvocations(fixture.runtime);
        var foreign = new LinkedHashMap<>(wire); foreign.put("headers", Map.of("Authorization", "foreign"));
        var oversized = new LinkedHashMap<>(wire); oversized.put("body", "x".repeat(32769));
        for (String invalid : List.of("[]", "{", json.writeValueAsString(oversized),
                json.writeValueAsString(foreign))) {
            assertEquals(400, mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                    .post("/api/apis/9/invocations").contentType("application/json").content(invalid))
                    .andReturn().getResponse().getStatus());
        }
        verifyNoInteractions(fixture.runtime);
    }
    @Test void fullDetailUsesOneAttestedOwnerReadAndNeverReloadsItsProject() {
        var fixture = fixture(ControlToolAclDecisionService.DECISION_ALLOW, HASH, true);
        var response = fixture.controller.detail(fixture.request, 9L);
        assertEquals(HttpStatus.OK, response.getStatusCode()); assertTrue(response.getHeaders().isEmpty());
        verify(fixture.capability).getHttpApi(eq(9L), eq("42"), anyMap());
        verify(fixture.capability, never()).getProjectById(anyLong(), anyString());
        when(fixture.capability.getHttpApi(eq(9L), eq("42"), anyMap())).thenReturn(ResponseEntity.ok(Map.of("summary", Map.of(
                "id", 9L, "projectId", 7L, "projectCode", "orders", "qualifiedName", REF, "environment", "dev"))));
        assertEquals(HttpStatus.BAD_GATEWAY, fixture.controller.detail(fixture.request, 9L).getStatusCode());
        verifyNoInteractions(fixture.runtime);
    }

    @Test void connectionWriteClaimsItsOriginalWriteGrantWithoutAddingAReadRequirement() {
        var fixture = fixture(ControlToolAclDecisionService.DECISION_ALLOW, HASH, true);
        var session = new PlatformAuthenticatedSession(new PlatformPrincipal(42L, "ops", "Ops"), "session", LocalDateTime.now().plusMinutes(5),
                List.of("OPERATOR"), List.of(PlatformPermissions.PLATFORM_WRITE),
                List.of(new PlatformPermissionGrant(PlatformPermissions.PLATFORM_WRITE, "PROJECT", "orders")));
        when(fixture.authorization.requirePermission(fixture.request, PlatformPermissions.PLATFORM_WRITE)).thenReturn(session);
        when(fixture.runtime.saveConnection(any(), eq("42"))).thenReturn(ResponseEntity.ok(Map.of("status", "CONFIGURED")));
        var response = fixture.controller.saveConnection(fixture.request, 9L,
                Map.of("origin", "https://orders.example.test", "authMode", "NONE", "expectedRevision", 1L));
        assertEquals(HttpStatus.OK, response.getStatusCode());
        verify(fixture.capability).getHttpApiIdentity(9L, "42", CatalogReadAuthorization.resourceBody(session, PlatformPermissions.PLATFORM_WRITE));
        verify(fixture.authorization, never()).requirePermission(fixture.request, PlatformPermissions.PLATFORM_READ);
        verify(fixture.capability, never()).getProjectById(anyLong(), anyString());
    }
    @Test void ungroupedApiQueryIsForwardedAfterTheProjectReadGrantAndMixedSelectionIsRejected() {
        Fixture fixture = fixture(ControlToolAclDecisionService.DECISION_ALLOW, HASH, true);
        fixture.request.setParameter("ungrouped", "true");
        when(fixture.capability.listHttpApis(7L, null, null, null, null, 2, 5, "42", null, true, grant()))
                .thenReturn(ownerPage(Map.of("records", List.of(), "total", 0)));
        assertEquals(HttpStatus.OK, fixture.controller.list(fixture.request, 7L, null, null, null, null, 2, 5).getStatusCode());
        verify(fixture.authorization).requireResourcePermission(fixture.request, PlatformPermissions.PLATFORM_READ, "PROJECT", null, "orders");
        verify(fixture.capability).listHttpApis(7L, null, null, null, null, 2, 5, "42", null, true, grant());
        verify(fixture.capability, never()).getProjectById(anyLong(), anyString());
        fixture.request.setParameter("groupId", "4");
        assertThrows(org.springframework.web.server.ResponseStatusException.class,
                () -> fixture.controller.list(fixture.request, 7L, null, null, null, null, 1, 20));
        verify(fixture.capability, never()).listHttpApis(7L, null, null, null, null, 1, 20, "42", 4L, true, grant());
    }
    private static final String HASH = "a".repeat(64);
    private static final String SOURCE = "b".repeat(64);
    private static final String REF = "orders.dev.GET./orders/{orderId}";

    @Test
    void forwardsValidatedGroupSelectionWithoutTurningAnEmptyOwnerResultIntoAnUnfilteredPage() {
        Fixture fixture = fixture(ControlToolAclDecisionService.DECISION_ALLOW, HASH, true);
        fixture.request.addParameter("groupId", "71");
        when(fixture.capability.listHttpApis(7L, "dev", "order", "GET", null, 2, 10, "42", 71L, false, grant()))
                .thenReturn(ownerPage(Map.of("records", List.of(), "total", 0)));
        ResponseEntity<Object> result = fixture.controller.list(fixture.request, 7L, "dev", "order", "GET", null, 2, 10);
        assertEquals(HttpStatus.OK, result.getStatusCode());
        assertEquals(0, ((Map<?, ?>) result.getBody()).get("total"));
        verify(fixture.capability).listHttpApis(7L, "dev", "order", "GET", null, 2, 10, "42", 71L, false, grant());
        verify(fixture.capability, never()).listHttpApis(7L, "dev", "order", "GET", null, 2, 10, "42", null, false, grant());
        verifyNoInteractions(fixture.runtime);
        fixture.request.setParameter("groupId", "invalid");
        assertEquals(HttpStatus.BAD_REQUEST, assertThrows(ResponseStatusException.class,
                () -> fixture.controller.list(fixture.request, 7L, null, null, null, null, 1, 20)).getStatusCode());
    }

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

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"WRITE", "IDEMPOTENT_WRITE", "IRREVERSIBLE"})
    void writeNeedsCurrentSourceAndExplicitConfirmationThenBindsBodyWithoutCallerAuthority(String sideEffect) {
        Fixture fixture = fixture(ControlToolAclDecisionService.DECISION_ALLOW, HASH, true);
        @SuppressWarnings("unchecked")
        Map<String, Object> summary = new LinkedHashMap<>((Map<String, Object>) ((Map<?, ?>) fixture.capability.getHttpApi(9L, "42", CatalogReadAuthorization.resourceBody(fixture.authorization.requirePermission(fixture.request, PlatformPermissions.PLATFORM_READ), PlatformPermissions.PLATFORM_READ)).getBody()).get("summary"));
        when(fixture.capability.getHttpApi(org.mockito.ArgumentMatchers.eq(9L), org.mockito.ArgumentMatchers.eq("42"), org.mockito.ArgumentMatchers.anyMap())).thenReturn(ownerPage(Map.of("summary", summary,
                "acceptedContract", Map.of("sideEffect", sideEffect))));
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
    void catalogReturnsOwnerPageWithoutReadingConnectionsOrInvocationHistory() {
        Fixture fixture = fixture(ControlToolAclDecisionService.DECISION_ALLOW, HASH, true);
        Map<String, Object> row = Map.of("id", 9L, "projectId", 7L, "projectCode", "orders",
                "qualifiedName", REF, "sourceStatus", "ACCEPTED");
        when(fixture.capability.listHttpApis(7L, null, null, null, null, 1, 20, "42", null, false, grant()))
                .thenReturn(ownerPage(Map.of("records", List.of(row), "total", 1)));
        ResponseEntity<Object> response = fixture.controller.list(fixture.request, 7L,
                null, null, null, null, 1, 20);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        @SuppressWarnings("unchecked")
        Map<String, Object> first = (Map<String, Object>) ((List<?>) ((Map<?, ?>) response.getBody())
                .get("records")).get(0);
        assertEquals(row, first);
        assertFalse(first.containsKey("connectionStatus"));
        assertFalse(first.containsKey("latestInvocationStatus"));
        assertFalse(first.containsKey("latestHttpStatus"));
        assertFalse(first.containsKey("latestInvocationAt"));
        verifyNoInteractions(fixture.runtime);
    }

    @Test
    void catalogRejectsAnOwnerPageContainingAnotherProject() {
        Fixture fixture = fixture(ControlToolAclDecisionService.DECISION_ALLOW, HASH, true);
        when(fixture.capability.listHttpApis(7L, null, null, null, null, 1, 20, "42", null, false, grant()))
                .thenReturn(ownerPage(Map.of("records", List.of(Map.of("id", 9L,
                        "projectId", 7L, "projectCode", "other", "qualifiedName", REF)), "total", 1)));

        assertEquals(HttpStatus.BAD_GATEWAY, fixture.controller.list(fixture.request, 7L,
                null, null, null, null, 1, 20).getStatusCode());
        verifyNoInteractions(fixture.runtime);
    }

    @Test
    void catalogRejectsDuplicateOwnerIdentities() {
        Fixture fixture = fixture(ControlToolAclDecisionService.DECISION_ALLOW, HASH, true);
        Map<String, Object> row = Map.of("id", 9L, "projectId", 7L, "projectCode", "orders",
                "qualifiedName", REF, "sourceStatus", "ACCEPTED");
        when(fixture.capability.listHttpApis(7L, null, null, null, null, 1, 20, "42", null, false, grant()))
                .thenReturn(ownerPage(Map.of("records", List.of(row, row), "total", 2)));

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
                PlatformPermissions.PLATFORM_WRITE, PlatformPermissions.CAPABILITY_INVOKE),
                List.of(new PlatformPermissionGrant(PlatformPermissions.PLATFORM_READ, "GLOBAL", "*"),
                        new PlatformPermissionGrant(PlatformPermissions.PLATFORM_WRITE, "GLOBAL", "*"),
                        new PlatformPermissionGrant(PlatformPermissions.CAPABILITY_INVOKE, "GLOBAL", "*")));
        when(authorization.requirePermission(eq(request), anyString())).thenReturn(session);
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("id", 9L); summary.put("projectId", 7L); summary.put("projectCode", "orders");
        summary.put("qualifiedName", REF); summary.put("environment", "dev");
        summary.put("sourceConfirmed", confirmed); summary.put("sourceStatus", "ACCEPTED");
        summary.put("candidateContractHash", candidateHash); summary.put("acceptedContractHash", HASH);
        summary.put("sourceSetRevision", SOURCE);
        when(capability.getHttpApi(org.mockito.ArgumentMatchers.eq(9L), org.mockito.ArgumentMatchers.eq("42"), org.mockito.ArgumentMatchers.anyMap())).thenReturn(ownerPage(Map.of("summary", summary,
                "acceptedContract", Map.of("sideEffect", "READ_ONLY"))));
        when(capability.getHttpApiIdentity(org.mockito.ArgumentMatchers.eq(9L), org.mockito.ArgumentMatchers.eq("42"), org.mockito.ArgumentMatchers.anyMap())).thenReturn(ownerPage(summary));
        when(capability.getProjectById(7L, "42"))
                .thenReturn(ResponseEntity.ok(Map.of("projectId", 7L, "projectCode", "orders")));
        when(acl.decide(eq(session.roles()), eq(7L), eq("orders"), eq("TOOL"), eq(REF))).thenReturn(decision);
        return new Fixture(new HttpApiConsoleController(capability, runtime, authorization, acl, new ObjectMapper()),
                request, capability, runtime, authorization);
    }

    private record Fixture(HttpApiConsoleController controller, MockHttpServletRequest request,
                           CapabilityReviewGateway capability, RuntimeHttpApiConsoleGateway runtime,
                           PlatformRequestAuthorization authorization) { }

    private Map<String, Object> grant() {
        return Map.of("catalogReadGrant", Map.of("global", true, "projects", List.of(), "requestedProjectId", 7L));
    }

    private ResponseEntity<Object> ownerPage(Object body) {
        return ResponseEntity.ok().header(CatalogReadAuthorization.SCOPE, "PROJECT")
                .header(CatalogReadAuthorization.PROJECT_ID, "7")
                .header(CatalogReadAuthorization.PROJECT_CODE, "orders").body(body);
    }

    @Test void connectionsUseCanonicalIdentityWithoutLoadingContractOrAnotherProjectRoundTrip() {
        var fixture = fixture(ControlToolAclDecisionService.DECISION_ALLOW, HASH, true);
        when(fixture.runtime.readConnection(any(), eq("42"))).thenReturn(ResponseEntity.ok(Map.of("status", "NONE")));
        assertEquals(HttpStatus.OK, fixture.controller.connection(fixture.request, 9L).getStatusCode());
        verify(fixture.capability).getHttpApiIdentity(org.mockito.ArgumentMatchers.eq(9L), org.mockito.ArgumentMatchers.eq("42"), org.mockito.ArgumentMatchers.anyMap());
        verify(fixture.capability, never()).getHttpApi(anyLong(), anyString(), org.mockito.ArgumentMatchers.anyMap());
        verify(fixture.capability, never()).getProjectById(anyLong(), anyString());
        verify(fixture.authorization).requireResourcePermission(fixture.request, PlatformPermissions.PLATFORM_READ, "PROJECT", null, "orders");
    }

    @Test void projectDefaultAndOverrideRemovalUseTheApiOwnerAndProjectWriteGrant() {
        var fixture = fixture(ControlToolAclDecisionService.DECISION_ALLOW, HASH, true);
        when(fixture.runtime.saveProjectDefaultConnection(any(), eq("42")))
                .thenReturn(ResponseEntity.ok(Map.of("connectionSource", "PROJECT_DEFAULT")));
        when(fixture.runtime.removeConnectionOverride(any(), eq("42")))
                .thenReturn(ResponseEntity.ok(Map.of("connectionSource", "PROJECT_DEFAULT")));
        when(fixture.runtime.readConnection(any(), eq("42"))).thenReturn(ResponseEntity.ok(Map.of(
                "status", "CONFIGURED", "projectDefault", Map.of("origin", "https://orders.example.test", "revision", 2))));

        assertEquals(HttpStatus.OK, fixture.controller.saveProjectDefaultConnection(fixture.request, 9L,
                Map.of("origin", "https://orders.example.test", "authMode", "NONE")).getStatusCode());
        var saved = ArgumentCaptor.forClass(HttpApiConsoleContracts.ConnectionCommand.class);
        verify(fixture.runtime).saveProjectDefaultConnection(saved.capture(), eq("42"));
        assertEquals(9L, saved.getValue().apiId()); assertEquals(7L, saved.getValue().projectId());
        assertEquals("orders", saved.getValue().projectCode()); assertEquals("dev", saved.getValue().environment());
        assertEquals("https://orders.example.test", saved.getValue().save().origin());
        assertNull(saved.getValue().save().expectedRevision());
        verify(fixture.authorization).requireResourcePermission(fixture.request, PlatformPermissions.PLATFORM_WRITE, "PROJECT", null, "orders");
        assertEquals(HttpStatus.BAD_REQUEST, fixture.controller.saveProjectDefaultConnection(fixture.request, 9L,
                Map.of("origin", "https://orders.example.test", "projectId", 8L)).getStatusCode());

        var read = fixture.controller.projectDefaultConnection(fixture.request, 9L);
        assertEquals(HttpStatus.OK, read.getStatusCode());
        assertEquals("https://orders.example.test", ((Map<?, ?>) read.getBody()).get("origin"));

        assertEquals(HttpStatus.BAD_REQUEST, fixture.controller.removeConnectionOverride(fixture.request, 9L, null).getStatusCode());
        assertEquals(HttpStatus.OK, fixture.controller.removeConnectionOverride(fixture.request, 9L, 3L).getStatusCode());
        var removal = ArgumentCaptor.forClass(HttpApiConsoleContracts.ConnectionCommand.class);
        verify(fixture.runtime).removeConnectionOverride(removal.capture(), eq("42"));
        assertEquals(3L, removal.getValue().save().expectedRevision());
        assertEquals(REF, removal.getValue().qualifiedName());
    }

    @Test void listWithMissingAttestedOwnerHeadersFailsClosedBeforeRuntime() {
        var fixture = fixture(ControlToolAclDecisionService.DECISION_ALLOW, HASH, true);
        when(fixture.capability.listHttpApis(7L, null, null, null, null, 1, 20, "42", null, false, grant()))
                .thenReturn(ResponseEntity.ok(Map.of("records", List.of(), "total", 0)));
        assertEquals(HttpStatus.BAD_GATEWAY, fixture.controller.list(fixture.request, 7L, null, null, null, null, 1, 20).getStatusCode());
        verifyNoInteractions(fixture.runtime);
    }
}
