package com.enterprise.ai.control.capability;

import com.enterprise.ai.common.capability.ConsoleCapabilityInvocationContracts;
import com.enterprise.ai.control.governance.ControlToolAclDecisionService;
import com.enterprise.ai.control.identity.PlatformAuthenticatedSession;
import com.enterprise.ai.control.identity.PlatformAuthorizationService;
import com.enterprise.ai.control.identity.PlatformDelegatedRequestScope;
import com.enterprise.ai.control.identity.PlatformPermissionGrant;
import com.enterprise.ai.control.identity.PlatformPrincipal;
import com.enterprise.ai.control.identity.PlatformRequestAuthorization;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CapabilityMcpPlatformInvocationAdapterTest {
    @AfterEach void clearRequest() { RequestContextHolder.resetRequestAttributes(); }

    @Test
    void sourceChangingFromReadToWriteCannotBeConfirmedByAiInput() {
        var fixture = fixture();
        when(fixture.capability.getBusinessMethodInvocationContext("orders.lookup", "42"))
                .thenReturn(ResponseEntity.ok(context("READ_ONLY")), ResponseEntity.ok(context("WRITE")));
        assertEquals(HttpStatus.OK, fixture.adapter.context(fixture.actor, "orders.lookup").getStatusCode());
        Map<String, Object> forged = new LinkedHashMap<>(request());
        forged.put("confirmedSideEffect", true);
        ResponseEntity<?> result = fixture.adapter.invokeReadOnly(fixture.actor, "orders.lookup", forged);
        assertEquals(HttpStatus.CONFLICT, result.getStatusCode());
        assertEquals("CONSOLE_CAPABILITY_CONFIRMATION_REQUIRED", ((Map<?, ?>) result.getBody()).get("code"));
        verify(fixture.runtime, never()).invoke(any(), any());
    }

    @Test
    void readOnlyTrialUsesAttestedActorAndKeepsUnknownOutcomeQueryable() {
        var fixture = fixture();
        when(fixture.capability.getBusinessMethodInvocationContext("orders.lookup", "42"))
                .thenReturn(ResponseEntity.ok(context("READ_ONLY")));
        when(fixture.runtime.invoke(any(), eq("42"))).thenThrow(new IllegalStateException("transport unavailable"));
        ResponseEntity<?> result = fixture.adapter.invokeReadOnly(fixture.actor, "orders.lookup", request());
        assertEquals(HttpStatus.ACCEPTED, result.getStatusCode());
        assertEquals("UNKNOWN", ((Map<?, ?>) result.getBody()).get("status"));
        assertEquals(true, ((Map<?, ?>) result.getBody()).get("queryable"));
        var command = ArgumentCaptor.forClass(ConsoleCapabilityInvocationContracts.InvocationCommand.class);
        verify(fixture.runtime).invoke(command.capture(), eq("42"));
        assertEquals("42", command.getValue().platformActorId());
        assertFalse(command.getValue().confirmedSideEffect());
        assertEquals("a".repeat(64), command.getValue().expectedContractHash());
    }

    @Test
    void httpTrialPinsTheCredentialAndCannotForgeSideEffectConfirmation() {
        var fixture = fixture();
        when(fixture.httpApis.connection(any(), eq(9L))).thenReturn(ResponseEntity.ok(Map.of(
                "credentialRef", "ref-1", "credentialRevision", "b".repeat(64), "revision", 3,
                "authMode", "API_KEY", "secret", "never-return")));
        Map<String, Object> exposed = (Map<String, Object>) fixture.adapter.httpConnection(fixture.actor, 9L).getBody();
        assertFalse(exposed.containsKey("secret"));
        assertFalse(exposed.containsKey("credentialRef"));
        assertEquals("b".repeat(64), exposed.get("credentialRevision"));
        Map<String, Object> input = new LinkedHashMap<>(request());
        input.put("confirmedSideEffect", true);
        assertEquals(HttpStatus.CONFLICT, fixture.adapter.invokeHttpReadOnly(fixture.actor, 9L, input).getStatusCode());
        verify(fixture.httpApis, never()).invoke(any(), eq(9L), any());

        input.put("expectedCredentialRevision", "b".repeat(64));
        when(fixture.httpApis.invoke(any(), eq(9L), any())).thenReturn(ResponseEntity.ok(Map.of("status", "ACCEPTED")));
        assertEquals(HttpStatus.OK, fixture.adapter.invokeHttpReadOnly(fixture.actor, 9L, input).getStatusCode());
        @SuppressWarnings("unchecked") var sent = ArgumentCaptor.forClass(Map.class);
        verify(fixture.httpApis).invoke(any(), eq(9L), sent.capture());
        assertEquals(false, sent.getValue().get("confirmedSideEffect"));
        assertTrue(sent.getValue().containsKey("expectedCredentialRevision"));
    }

    @Test
    void httpWritePreflightChecksConnectionOwnerAndDoesNotExposeCredentialReference() {
        var fixture = fixture();
        Map<String, Object> target = Map.ofEntries(Map.entry("apiId", 9L), Map.entry("projectId", 7L),
                Map.entry("projectCode", "orders"), Map.entry("qualifiedName", "orders.api"),
                Map.entry("environment", "dev"), Map.entry("httpMethod", "POST"), Map.entry("routeTemplate", "/orders"),
                Map.entry("sideEffect", "WRITE"), Map.entry("acceptedContractHash", "a".repeat(64)),
                Map.entry("sourceSetRevision", "d".repeat(64)));
        when(fixture.httpApis.platformAiWriteContext(any(), eq(9L))).thenReturn(ResponseEntity.ok(target));
        Map<String, Object> connection = new LinkedHashMap<>();
        connection.put("projectId", 7L); connection.put("projectCode", "orders");
        connection.put("qualifiedName", "orders.api"); connection.put("environment", "dev");
        connection.put("status", "CONFIGURED"); connection.put("authMode", "API_KEY");
        connection.put("credentialRef", "private-credential-ref"); connection.put("credentialRevision", "b".repeat(64));
        connection.put("revision", 3L); connection.put("previewUrl", "https://orders.example.test/orders");
        when(fixture.httpApis.connection(any(), eq(9L))).thenReturn(ResponseEntity.ok(connection));
        var result = fixture.adapter.httpWriteContext(fixture.actor, 9L);
        assertEquals(HttpStatus.OK, result.getStatusCode());
        var projection = (Map<?, ?>) result.getBody();
        assertEquals(true, projection.get("credentialRequired"));
        assertEquals("b".repeat(64), projection.get("credentialRevision"));
        assertFalse(projection.toString().contains("private-credential-ref"));
        connection.put("projectCode", "other");
        assertEquals(HttpStatus.CONFLICT, fixture.adapter.httpWriteContext(fixture.actor, 9L).getStatusCode());
    }

    @Test
    void approvedHttpDispatchUsesThePinnedCredentialAndSetsConfirmationInsideTheAdapter() {
        var fixture = fixture();
        when(fixture.httpApis.connection(any(), eq(9L))).thenReturn(ResponseEntity.ok(Map.of(
                "credentialRef", "ref-1", "credentialRevision", "b".repeat(64), "revision", 3)));
        when(fixture.httpApis.invoke(any(), eq(9L), any())).thenReturn(ResponseEntity.ok(Map.of("status", "ACCEPTED")));
        var body = new LinkedHashMap<String, Object>();
        body.put("expectedCredentialRevision", "b".repeat(64)); body.put("confirmedSideEffect", false);
        assertEquals(HttpStatus.OK, fixture.adapter.invokeHttpApproved(fixture.actor, 9L, body).getStatusCode());
        @SuppressWarnings("unchecked") var sent = ArgumentCaptor.forClass(Map.class);
        verify(fixture.httpApis).invoke(any(), eq(9L), sent.capture());
        assertEquals(true, sent.getValue().get("confirmedSideEffect"));
        assertEquals("b".repeat(64), sent.getValue().get("expectedCredentialRevision"));
        body.put("expectedCredentialRevision", "c".repeat(64));
        assertEquals(HttpStatus.CONFLICT, fixture.adapter.invokeHttpApproved(fixture.actor, 9L, body).getStatusCode());
    }

    @Test
    void realHttpConsoleRejectsAWriteApiEvenIfAiAttemptsToConfirmIt() {
        var base = fixture();
        var capability = mock(CapabilityReviewGateway.class);
        var httpRuntime = mock(RuntimeHttpApiConsoleGateway.class);
        var acl = mock(ControlToolAclDecisionService.class);
        var auth = new PlatformRequestAuthorization(new PlatformAuthorizationService(null, null, null, null));
        var summary = new LinkedHashMap<String, Object>();
        summary.put("id", 9L); summary.put("projectId", 7L); summary.put("projectCode", "orders");
        summary.put("qualifiedName", "orders.api"); summary.put("environment", "dev");
        summary.put("sourceConfirmed", true); summary.put("sourceStatus", "ACCEPTED");
        summary.put("candidateContractHash", "a".repeat(64)); summary.put("acceptedContractHash", "a".repeat(64));
        summary.put("sourceSetRevision", "d".repeat(64));
        when(capability.getHttpApiIdentity(eq(9L), eq("42"), any())).thenReturn(ownerPage(summary));
        when(capability.getHttpApi(eq(9L), eq("42"), any())).thenReturn(ownerPage(Map.of(
                "summary", summary, "acceptedContract", Map.of("sideEffect", "WRITE"))));
        when(httpRuntime.readConnection(any(), eq("42")))
                .thenReturn(ResponseEntity.ok(Map.of("authMode", "NONE", "revision", 3)));
        when(acl.decide(base.actor.roles(), 7L, "orders", "TOOL", "orders.api"))
                .thenReturn(ControlToolAclDecisionService.DECISION_ALLOW);
        var apiController = new HttpApiConsoleController(capability, httpRuntime, auth, acl, new ObjectMapper());
        var adapter = new CapabilityMcpPlatformInvocationAdapter(mock(CapabilityInvocationConsoleController.class),
                apiController, new PlatformDelegatedRequestScope(), new ObjectMapper());
        var input = new LinkedHashMap<String, Object>();
        input.put("invocationId", "00000000-0000-0000-0000-000000000001");
        input.put("expectedContractHash", "a".repeat(64)); input.put("expectedSourceSetRevision", "d".repeat(64));
        input.put("connectionRevision", 3); input.put("confirmedSideEffect", true);

        ResponseEntity<?> response = adapter.invokeHttpReadOnly(base.actor, 9L, input);
        assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
        assertEquals("HTTP_API_CONFIRMATION_REQUIRED", ((Map<?, ?>) response.getBody()).get("code"));
        verify(httpRuntime, never()).invoke(any(), any());
    }

    private Fixture fixture() {
        var capability = mock(CapabilityReviewGateway.class);
        var runtime = mock(RuntimeConsoleCapabilityInvocationGateway.class);
        var acl = mock(ControlToolAclDecisionService.class);
        var authorization = new PlatformRequestAuthorization(new PlatformAuthorizationService(null, null, null, null));
        var actor = new PlatformAuthenticatedSession(new PlatformPrincipal(42L, "operator", "Operator"), "pai_19",
                LocalDateTime.now().plusDays(1), List.of("OPERATOR"), List.of("platform:read", "capability:invoke"),
                List.of(new PlatformPermissionGrant("platform:read", "PROJECT", "orders"),
                        new PlatformPermissionGrant("capability:invoke", "PROJECT", "orders")));
        when(acl.decide(actor.roles(), 7L, "orders", "TOOL", "orders.lookup"))
                .thenReturn(ControlToolAclDecisionService.DECISION_ALLOW);
        var controller = new CapabilityInvocationConsoleController(capability, runtime, authorization, acl, new ObjectMapper());
        var httpApis = mock(HttpApiConsoleController.class);
        var adapter = new CapabilityMcpPlatformInvocationAdapter(controller, httpApis,
                new PlatformDelegatedRequestScope(), new ObjectMapper());
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest()));
        return new Fixture(adapter, actor, capability, runtime, httpApis);
    }

    private static Map<String, Object> request() {
        return Map.of("invocationId", "00000000-0000-0000-0000-000000000001",
                "expectedContractHash", "a".repeat(64), "expectedExecutionRevision", "d".repeat(64),
                "input", Map.of("orderNo", "fixture-order"));
    }

    private static Map<String, Object> context(String sideEffect) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("contractVersion", 1); result.put("name", "orders.lookup");
        result.put("qualifiedName", "orders.lookup"); result.put("sourceQualifiedName", "orders.lookup");
        result.put("assetType", "BUSINESS_METHOD"); result.put("projectId", 7L); result.put("projectCode", "orders");
        result.put("currentContractHash", "a".repeat(64)); result.put("acceptedContractHash", "a".repeat(64));
        result.put("sourceContractHash", "a".repeat(64)); result.put("executionRevision", "d".repeat(64));
        result.put("sourceAvailability", "READY"); result.put("enabled", true); result.put("sideEffect", sideEffect);
        result.put("parameters", List.of()); result.put("credentialAvailable", true);
        result.put("businessIdentityRequired", false); result.put("executable", true); result.put("timeoutMs", 30000L);
        return result;
    }

    private static ResponseEntity<Object> ownerPage(Object body) {
        return ResponseEntity.ok().header(CatalogReadAuthorization.SCOPE, "PROJECT")
                .header(CatalogReadAuthorization.PROJECT_ID, "7")
                .header(CatalogReadAuthorization.PROJECT_CODE, "orders").body(body);
    }

    private record Fixture(CapabilityMcpPlatformInvocationAdapter adapter, PlatformAuthenticatedSession actor,
                           CapabilityReviewGateway capability, RuntimeConsoleCapabilityInvocationGateway runtime,
                           HttpApiConsoleController httpApis) { }
}
