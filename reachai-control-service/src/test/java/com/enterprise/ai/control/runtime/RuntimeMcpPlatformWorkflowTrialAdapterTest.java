package com.enterprise.ai.control.runtime;

import com.enterprise.ai.common.capability.WorkflowReadOnlyTrialPolicy;
import com.enterprise.ai.control.capability.CapabilityReviewGateway;
import com.enterprise.ai.control.client.capability.CapabilityProjectOnboardingClient;
import com.enterprise.ai.control.client.runtime.RuntimeProxyClient;
import com.enterprise.ai.control.governance.ControlToolAclDecisionService;
import com.enterprise.ai.control.identity.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.server.ResponseStatusException;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RuntimeMcpPlatformWorkflowTrialAdapterTest {
    private static final String ID = "00000000-0000-0000-0000-000000000007";
    private final RuntimeProxyClient runtime = mock(RuntimeProxyClient.class);
    private final CapabilityProjectOnboardingClient projects = mock(CapabilityProjectOnboardingClient.class);
    private final ControlWorkflowReadOnlyTrialGateway gateway = mock(ControlWorkflowReadOnlyTrialGateway.class);
    private RuntimeMcpPlatformWorkflowTrialAdapter adapter;

    @BeforeEach void prepare() {
        var access = new RuntimeManagementAccess(new PlatformRequestAuthorization(new PlatformAuthorizationService(null, null, null, null)), projects);
        var controller = new ControlWorkflowReadOnlyTrialController(runtime, mock(CapabilityReviewGateway.class), access,
                mock(ControlToolAclDecisionService.class), gateway, new ObjectMapper());
        adapter = new RuntimeMcpPlatformWorkflowTrialAdapter(controller, runtime, access, new RuntimeMcpActorScope());
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest()));
        when(projects.getProjectById(12L)).thenReturn(Map.of("id", 12L, "projectCode", "orders"));
        when(runtime.workflowWorkingCopy("wf-1")).thenReturn(ResponseEntity.ok(Map.of("workflowId", "wf-1",
                "projectId", 12L, "projectCode", "orders", "revision", "saved-r1", "graphSpecJson",
                "{\"nodes\":[{\"id\":\"answer\",\"type\":\"ANSWER\"}]}")));
        when(gateway.run(any())).thenAnswer(call -> {
            WorkflowReadOnlyTrialPolicy.TrialCommand command = call.getArgument(0);
            return ResponseEntity.ok(Map.of("success", true, "workflowId", "wf-1", "traceId", command.platformTrialTraceId()));
        });
    }

    @AfterEach void close() { RequestContextHolder.resetRequestAttributes(); }

    @Test void reusesStudioAttestationAndDerivesTheRunIdentityFromTheConnection() {
        var identity = identity(19L, "workflow:debug", "capability:invoke");
        assertEquals(HttpStatus.OK, adapter.run(identity, ID, body()).getStatusCode());
        var signed = ArgumentCaptor.forClass(WorkflowReadOnlyTrialPolicy.TrialCommand.class);
        verify(gateway).run(signed.capture());
        assertEquals("7", signed.getValue().platformActorId());
        assertEquals(WorkflowReadOnlyTrialPolicy.PLATFORM_AI_VERSION, signed.getValue().contractVersion());
        assertEquals(RuntimeMcpPlatformWorkflowTrialAdapter.traceId(identity, ID), signed.getValue().platformTrialTraceId());
        assertNotEquals(RuntimeMcpPlatformWorkflowTrialAdapter.traceId(identity(20L, "workflow:debug", "capability:invoke"), ID),
                signed.getValue().platformTrialTraceId());
        assertEquals(Map.of("orderNo", "订单-7"), signed.getValue().inputParams());
        assertNull(((ServletRequestAttributes) RequestContextHolder.getRequestAttributes()).getRequest()
                .getAttribute(PlatformConsoleAuthInterceptor.SESSION_REQUEST_ATTRIBUTE));
    }

    @Test void missingCapabilityGrantOrInjectedTrustCannotDispatch() {
        assertEquals(HttpStatus.FORBIDDEN, assertThrows(ResponseStatusException.class,
                () -> adapter.run(identity(19L, "workflow:debug"), ID, body())).getStatusCode());
        var forged = new LinkedHashMap<>(body()); forged.put("platformTrialTraceId", "forged");
        assertEquals(HttpStatus.BAD_REQUEST, adapter.run(identity(19L, "workflow:debug", "capability:invoke"), ID, forged).getStatusCode());
        verifyNoInteractions(gateway);
    }

    @Test void statusIsScopedToTheConnectionAndProjectsAndReturnsNoRunPayload() {
        var actor = identity(19L, "workflow:debug", "capability:invoke");
        String trace = RuntimeMcpPlatformWorkflowTrialAdapter.traceId(actor, ID);
        var summary = new LinkedHashMap<String, Object>(Map.of("traceId", trace, "workflowId", "wf-1", "runType", "WORKFLOW",
                "entryType", "STUDIO_READ_ONLY_TRIAL", "projectCode", "orders", "status", "COMPLETED", "outputSummary", "private-output"));
        when(runtime.runOpsDetail(trace)).thenReturn(ResponseEntity.ok(Map.of("summary", summary, "spans", List.of("private-span"))));
        var response = adapter.status(actor, ID, "wf-1");
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("COMPLETED", ((Map<?, ?>) response.getBody()).get("status"));
        assertFalse(response.getBody().toString().contains("private"));
        summary.put("projectCode", "billing");
        assertEquals(HttpStatus.FORBIDDEN, assertThrows(ResponseStatusException.class,
                () -> adapter.status(actor, ID, "wf-1")).getStatusCode());
        when(runtime.runOpsDetail(trace)).thenReturn(ResponseEntity.badRequest().build());
        assertEquals("NOT_FOUND_OR_PENDING", ((Map<?, ?>) adapter.status(actor, ID, "wf-1").getBody()).get("status"));
    }

    private static Map<String, Object> body() {
        return Map.of("workflowId", "wf-1", "expectedRevision", "saved-r1", "inputParams", Map.of("orderNo", "订单-7"));
    }

    private static PlatformAiConnectionService.ConnectionIdentity identity(long connection, String... permissions) {
        var user = new PlatformUserEntity(); user.setId(7L);
        var actor = new PlatformAuthenticatedSession(PlatformPrincipal.fromUser(user), "pai_" + connection, null,
                List.of("OPERATOR"), List.of(permissions), Arrays.stream(permissions)
                .map(code -> new PlatformPermissionGrant(code, "PROJECT", "orders")).toList());
        return new PlatformAiConnectionService.ConnectionIdentity(connection, "CODEX", actor);
    }
}
