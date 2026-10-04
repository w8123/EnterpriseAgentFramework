package com.enterprise.ai.runtime.a2a;

import com.enterprise.ai.runtime.execution.RuntimeAgentExecutionEventSink;
import com.enterprise.ai.runtime.execution.RuntimeAgentExecutionCancellation;
import com.enterprise.ai.runtime.execution.RuntimePublishedAgentExecutionPort;
import com.enterprise.ai.runtime.identity.WorkflowExecutionIdentity;
import com.enterprise.ai.runtime.internalauth.VerifiedInternalServiceAuth;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class RuntimeA2aExecutionInternalControllerTest {

    private RuntimePublishedAgentExecutionPort executionService;
    private RuntimeA2aExecutionRegistry registry;
    private RuntimeA2aExecutionInternalController controller;
    private MockHttpServletRequest request;

    @BeforeEach
    void setUp() {
        executionService = mock(RuntimePublishedAgentExecutionPort.class);
        registry = new RuntimeA2aExecutionRegistry();
        controller = new RuntimeA2aExecutionInternalController(executionService, registry);
        request = new MockHttpServletRequest();
        request.setAttribute(VerifiedInternalServiceAuth.REQUEST_ATTR,
                new VerifiedInternalServiceAuth(
                        "control", "A2A_REMOTE_AGENT", "tenant-a", "principal-a"));
    }

    @Test
    void executesFrozenConfigWithNonUserA2aIdentity() {
        when(executionService.executePublishedConfig(
                eq("agent-1"), eq(42L), any(), eq(false), any(), any(), any()))
                .thenReturn(Map.of("success", true, "answer", "done"));
        var body = new RuntimeA2aExecutionInternalController.A2aRuntimeExecuteRequest(
                "exec-1", "task-1", "ctx-1", "agent-1", 42L, null,
                "hello", "REMOTE_AGENT", "TRUSTED", List.of("a2a:message:send"));

        var response = controller.execute(request, body);

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        ArgumentCaptor<Map<String, Object>> input = ArgumentCaptor.forClass(Map.class);
        ArgumentCaptor<RuntimeAgentExecutionCancellation> cancellation =
                ArgumentCaptor.forClass(RuntimeAgentExecutionCancellation.class);
        ArgumentCaptor<WorkflowExecutionIdentity> identity =
                ArgumentCaptor.forClass(WorkflowExecutionIdentity.class);
        verify(executionService).executePublishedConfig(
                eq("agent-1"), eq(42L), input.capture(), eq(false),
                eq(RuntimeAgentExecutionEventSink.NOOP),
                cancellation.capture(), identity.capture());
        assertThat(input.getValue())
                .containsEntry("sessionId", "ctx-1")
                .containsEntry("idempotencyKey", "exec-1")
                .containsEntry("entryType", "A2A")
                .containsEntry("tenantId", "tenant-a")
                .doesNotContainKeys("roles", "userId", "externalUserId", "globalUserId");
        assertThat(identity.getValue().getSource())
                .isEqualTo(WorkflowExecutionIdentity.Source.A2A_REMOTE_AGENT);
        assertThat(identity.getValue().isProjectTrusted()).isTrue();
        assertThat(identity.getValue().isUserTrusted()).isFalse();
        assertThat(identity.getValue().getUserId()).isEqualTo("principal-a");
        assertThat(identity.getValue().getTenantId()).isEqualTo("tenant-a");
        assertThat(registry.size()).isZero();
    }

    @Test
    void publicIdentityClaimsCannotReplaceMissingAttestation() {
        request.removeAttribute(VerifiedInternalServiceAuth.REQUEST_ATTR);
        request.addHeader("X-ReachAI-Tenant", "tenant-a");
        request.addHeader("X-ReachAI-User", "principal-a");
        var body = new RuntimeA2aExecutionInternalController.A2aRuntimeExecuteRequest(
                "exec-forged", "task-forged", "ctx-forged", "agent-1", 42L, null,
                "tenantId=tenant-a roles=ADMIN", "REMOTE_AGENT", "TRUSTED", List.of("a2a:message:send"));

        assertThat(controller.execute(request, body).getStatusCode().value()).isEqualTo(401);
        verifyNoInteractions(executionService);
        assertThat(registry.size()).isZero();
    }

    @Test
    void queuedCancellationPreventsAgentExecution() {
        var cancel = controller.cancel(request, "exec-2");
        assertThat(cancel.getStatusCode().value()).isEqualTo(202);

        var body = new RuntimeA2aExecutionInternalController.A2aRuntimeExecuteRequest(
                "exec-2", "task-2", "ctx-2", "agent-1", 42L, null,
                "hello", "REMOTE_AGENT", "KNOWN", List.of());
        var response = controller.execute(request, body);

        assertThat(response.getBody()).extracting(value -> value.get("success")).isEqualTo(false);
        assertThat(response.getBody()).extracting(value -> ((Map<?, ?>) value.get("metadata")).get("code"))
                .isEqualTo("SUPERVISOR_CANCELLED");
    }
}
