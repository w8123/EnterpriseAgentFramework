package com.enterprise.ai.runtime.runops.consolecapability;

import com.enterprise.ai.common.capability.CapabilityInvocationFailureCategory;
import com.enterprise.ai.common.capability.CapabilityInvocationResponse;
import com.enterprise.ai.common.capability.CapabilityInvocationStatus;
import com.enterprise.ai.common.capability.ConsoleCapabilityInvocationContracts;
import com.enterprise.ai.control.capability.CapabilityInvocationConsoleController;
import com.enterprise.ai.control.capability.CapabilityReviewGateway;
import com.enterprise.ai.control.capability.RuntimeConsoleCapabilityInvocationGateway;
import com.enterprise.ai.control.governance.ControlToolAclDecisionService;
import com.enterprise.ai.control.identity.PlatformAuthenticatedSession;
import com.enterprise.ai.control.identity.PlatformPermissionGrant;
import com.enterprise.ai.control.identity.PlatformPermissions;
import com.enterprise.ai.control.identity.PlatformPrincipal;
import com.enterprise.ai.control.identity.PlatformRequestAuthorization;
import com.enterprise.ai.runtime.client.capability.RuntimeCapabilityCatalogClient;
import com.enterprise.ai.runtime.runops.RuntimeRunMapper;
import com.enterprise.ai.runtime.support.RuntimeQueryTestDatabase;
import com.enterprise.ai.runtime.trace.RuntimeTraceRootService;
import com.enterprise.ai.runtime.trace.RuntimeTraceSpanMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Cross-service test boundary: an actual Runtime H2 claim/outcome is handed to Control's
 * public validator. No idealized response map is hand-written for the lifecycle check.
 */
class ConsoleCapabilityInvocationControlLifecycleIntegrationTest {

    private final ObjectMapper json = new ObjectMapper();
    private RuntimeQueryTestDatabase database;
    private RuntimeCapabilityCatalogClient capabilities;
    private ConsoleCapabilityInvocationService runtime;
    private DataSourceTransactionManager transactions;

    @BeforeEach
    void setUp() throws Exception {
        database = new RuntimeQueryTestDatabase(List.of(
                "runtime_run", "runtime_trace_span", "runtime_console_capability_invocation"),
                RuntimeRunMapper.class, RuntimeTraceSpanMapper.class, ConsoleCapabilityInvocationMapper.class);
        capabilities = mock(RuntimeCapabilityCatalogClient.class);
        transactions = new DataSourceTransactionManager(database.jdbc().getDataSource());
        runtime = new ConsoleCapabilityInvocationService(
                database.mapper(ConsoleCapabilityInvocationMapper.class),
                database.mapper(RuntimeRunMapper.class),
                new RuntimeTraceRootService(database.mapper(RuntimeTraceSpanMapper.class), json),
                capabilities, json, transactions);
    }

    @AfterEach
    void close() {
        if (database != null) database.close();
    }

    @Test
    void realPersistedAcceptedClaimIsAcceptedByControlDuringThePreDispatchWindow() throws Exception {
        String invocationId = UUID.randomUUID().toString();
        ConsoleCapabilityInvocationContracts.InvocationCommand command = command(invocationId, Map.of("orderNo", "O-window"), List.of());
        persistClaim(command);

        assertEquals("ACCEPTED", database.jdbc().queryForObject(
                "SELECT status FROM runtime_console_capability_invocation WHERE invocation_id = ?", String.class, invocationId));
        assertEquals("PERSISTED", database.jdbc().queryForObject(
                "SELECT dispatch_stage FROM runtime_console_capability_invocation WHERE invocation_id = ?", String.class, invocationId));
        ConsoleCapabilityInvocationContracts.InvocationOutcome persisted = runtime.get(invocationId, "42");
        assertNotNull(persisted);
        assertEquals("ACCEPTED", persisted.status());
        assertEquals("PERSISTED", persisted.dispatchStage());
        assertFalse(persisted.terminal());

        ControlFixture control = controlFixture();
        when(control.runtimeGateway().get(invocationId, "42"))
                .thenAnswer(call -> ResponseEntity.ok(wire(runtime.get(invocationId, "42"))));
        ResponseEntity<Object> publicRead = control.controller().get(control.request(), invocationId);
        assertEquals(HttpStatus.OK, publicRead.getStatusCode());
        assertEquals("PERSISTED", ((Map<?, ?>) publicRead.getBody()).get("dispatchStage"));

        // This is the exact durable window between claim commit and dispatch. Concurrent
        // retries read the real claim and must not create an outbound operation.
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<ConsoleCapabilityInvocationContracts.InvocationOutcome> first = pool.submit(() -> runtime.invoke(command));
            Future<ConsoleCapabilityInvocationContracts.InvocationOutcome> second = pool.submit(() -> runtime.invoke(command));
            assertEquals("PERSISTED", first.get(3, TimeUnit.SECONDS).dispatchStage());
            assertEquals("PERSISTED", second.get(3, TimeUnit.SECONDS).dispatchStage());
        } finally {
            pool.shutdownNow();
        }
        verifyNoInteractions(capabilities);
    }

    @Test
    void controlGeneratedNestedDtoSensitiveAliasesRedactFlatAndWrappedInputsInRuntime() {
        ControlFixture control = controlFixture();
        when(control.capabilityGateway().getBusinessMethodInvocationContext("orders.lookup", "42"))
                .thenReturn(ResponseEntity.ok(nestedDtoContext()));
        List<ConsoleCapabilityInvocationContracts.InvocationCommand> forwarded = new ArrayList<>();
        when(control.runtimeGateway().invoke(any(), eq("42"))).thenAnswer(call -> {
            ConsoleCapabilityInvocationContracts.InvocationCommand command = call.getArgument(0);
            forwarded.add(command);
            return ResponseEntity.ok(wire(runtime.invoke(command)));
        });
        when(capabilities.invokeTool(eq("orders.lookup"), anyMap())).thenAnswer(call -> {
            Map<?, ?> request = call.getArgument(1);
            String invocationId = String.valueOf(request.get("invocationId"));
            return success(invocationId, Map.of("echo", request.get("input"), "alias", request.get("input")));
        });

        String flatPhone = "flat-nested-phone";
        String flatArrayPhone = "flat-array-phone";
        ResponseEntity<Object> flat = control.controller().invoke(control.request(), "orders.lookup", request(
                Map.of("shipping", Map.of("phone", flatPhone),
                        "items", List.of(Map.of("phone", flatArrayPhone, "sku", "normal-flat")))));
        assertEquals(HttpStatus.OK, flat.getStatusCode());
        assertSensitiveAliases(forwarded.get(0));
        assertRedacted(flat, flatPhone);
        assertRedacted(flat, flatArrayPhone);

        String wrappedPhone = "wrapped-nested-phone";
        String wrappedArrayPhone = "wrapped-array-phone";
        ResponseEntity<Object> wrapped = control.controller().invoke(control.request(), "orders.lookup", request(
                Map.of("request", Map.of("shipping", Map.of("phone", wrappedPhone),
                        "items", List.of(Map.of("phone", wrappedArrayPhone, "sku", "normal-wrapped"))))));
        assertEquals(HttpStatus.OK, wrapped.getStatusCode());
        assertSensitiveAliases(forwarded.get(1));
        assertRedacted(wrapped, wrappedPhone);
        assertRedacted(wrapped, wrappedArrayPhone);
    }

    private void persistClaim(ConsoleCapabilityInvocationContracts.InvocationCommand command) {
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            Object claim = ReflectionTestUtils.invokeMethod(runtime, "claimInTransaction", command);
            assertNotNull(claim);
        });
    }

    private void assertSensitiveAliases(ConsoleCapabilityInvocationContracts.InvocationCommand command) {
        assertTrue(command.sensitiveInputNames().containsAll(List.of(
                "request.shipping.phone", "request.items[].phone", "shipping.phone", "items[].phone")));
    }

    private void assertRedacted(ResponseEntity<Object> response, String sentinel) {
        assertFalse(String.valueOf(response.getBody()).contains(sentinel));
        String persisted = String.join(" ", database.jdbc().queryForList(
                "SELECT CONCAT(COALESCE(result_json,''),' ',COALESCE(error_message,'')) "
                        + "FROM runtime_console_capability_invocation", String.class));
        assertFalse(persisted.contains(sentinel));
    }

    private Map<String, Object> request(Map<String, Object> input) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("invocationId", UUID.randomUUID().toString());
        body.put("expectedContractHash", "a".repeat(64));
        body.put("input", input);
        body.put("confirmedSideEffect", false);
        return body;
    }

    private ConsoleCapabilityInvocationContracts.InvocationCommand command(String invocationId,
                                                                             Map<String, Object> input,
                                                                             List<String> sensitive) {
        return new ConsoleCapabilityInvocationContracts.InvocationCommand(
                ConsoleCapabilityInvocationContracts.CONTRACT_VERSION, invocationId, "42", 7L, "orders",
                "orders.lookup", "a".repeat(64), input, sensitive, "READ_ONLY", false,
                System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(30));
    }

    private CapabilityInvocationResponse success(String invocationId, Object data) {
        return new CapabilityInvocationResponse(1, invocationId, "orders.lookup", "lookup", "Lookup",
                CapabilityInvocationStatus.SUCCEEDED, true, data, null, null,
                CapabilityInvocationFailureCategory.NONE, false, 1L, 1, null, Map.of("statusCode", 200));
    }

    private Map<String, Object> wire(ConsoleCapabilityInvocationContracts.InvocationOutcome outcome) {
        return json.convertValue(outcome, new TypeReference<>() { });
    }

    private ControlFixture controlFixture() {
        CapabilityReviewGateway capabilityGateway = mock(CapabilityReviewGateway.class);
        RuntimeConsoleCapabilityInvocationGateway runtimeGateway = mock(RuntimeConsoleCapabilityInvocationGateway.class);
        PlatformRequestAuthorization authorization = mock(PlatformRequestAuthorization.class);
        ControlToolAclDecisionService acl = mock(ControlToolAclDecisionService.class);
        MockHttpServletRequest request = new MockHttpServletRequest();
        PlatformAuthenticatedSession session = new PlatformAuthenticatedSession(
                new PlatformPrincipal(42L, "ops", "Ops"), "session", LocalDateTime.now().plusMinutes(5),
                List.of("OPERATOR"), List.of(PlatformPermissions.PLATFORM_READ, PlatformPermissions.CAPABILITY_INVOKE),
                List.of(new PlatformPermissionGrant(PlatformPermissions.PLATFORM_READ, "GLOBAL", "*"),
                        new PlatformPermissionGrant(PlatformPermissions.CAPABILITY_INVOKE, "GLOBAL", "*")));
        when(authorization.requirePermission(eq(request), anyString())).thenReturn(session);
        when(acl.decide(any(), eq(7L), eq("orders"), eq("TOOL"), eq("orders.lookup")))
                .thenReturn(ControlToolAclDecisionService.DECISION_ALLOW);
        return new ControlFixture(new CapabilityInvocationConsoleController(capabilityGateway, runtimeGateway,
                authorization, acl, json), capabilityGateway, runtimeGateway, request);
    }

    private Map<String, Object> nestedDtoContext() {
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("contractVersion", ConsoleCapabilityInvocationContracts.CONTRACT_VERSION);
        context.put("name", "orders.lookup");
        context.put("qualifiedName", "orders.lookup");
        context.put("sourceQualifiedName", "orders.lookup");
        context.put("assetType", "BUSINESS_METHOD");
        context.put("projectId", 7L);
        context.put("projectCode", "orders");
        context.put("currentContractHash", "a".repeat(64));
        context.put("acceptedContractHash", "a".repeat(64));
        context.put("sourceContractHash", "a".repeat(64));
        context.put("sourceAvailability", "READY");
        context.put("enabled", true);
        context.put("sideEffect", "READ_ONLY");
        context.put("parameters", List.of(Map.of("name", "request", "type", "object", "required", true,
                "children", List.of(
                        Map.of("name", "shipping", "type", "object", "required", false, "children", List.of(
                                Map.of("name", "phone", "type", "string", "required", false,
                                        "children", List.of(), "metadata", Map.of("sensitive", true))), "metadata", Map.of()),
                        Map.of("name", "items", "type", "array", "required", false, "children", List.of(
                                Map.of("name", "phone", "type", "string", "required", false,
                                        "children", List.of(), "metadata", Map.of("sensitive", true))), "metadata", Map.of())),
                "metadata", Map.of())));
        context.put("requestBodyType", "json");
        context.put("responseType", "json");
        context.put("targetDescription", "POST · 已接受项目契约");
        context.put("targetInstanceStatus", "READY");
        context.put("credentialAvailable", true);
        context.put("businessIdentityRequired", false);
        context.put("executable", true);
        context.put("timeoutMs", 30_000L);
        return context;
    }

    private record ControlFixture(CapabilityInvocationConsoleController controller,
                                  CapabilityReviewGateway capabilityGateway,
                                  RuntimeConsoleCapabilityInvocationGateway runtimeGateway,
                                  MockHttpServletRequest request) { }
}
