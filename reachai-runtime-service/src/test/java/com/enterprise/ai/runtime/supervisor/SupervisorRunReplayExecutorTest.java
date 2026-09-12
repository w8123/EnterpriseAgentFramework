package com.enterprise.ai.runtime.supervisor;

import com.enterprise.ai.runtime.agent.RuntimeAgentConfigSnapshot;
import com.enterprise.ai.runtime.agent.RuntimeAgentExecutionContext;
import com.enterprise.ai.runtime.agent.RuntimeAgentExecutionContextResolver;
import com.enterprise.ai.runtime.agent.RuntimeAgentExecutionView;
import com.enterprise.ai.runtime.execution.RuntimeAgentExecutionCancellation;
import com.enterprise.ai.runtime.execution.RuntimeAgentRunLifecyclePort;
import com.enterprise.ai.runtime.execution.RuntimeInteractionResumeService;
import com.enterprise.ai.runtime.execution.RuntimePublishedAgentExecutionPort;
import com.enterprise.ai.runtime.execution.RuntimeSessionClearPort;
import com.enterprise.ai.runtime.execution.RuntimeSupervisorApprovalPort;
import com.enterprise.ai.runtime.identity.WorkflowExecutionIdentity;
import com.enterprise.ai.runtime.runops.RuntimeRunReplayExecutionPort;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/** Wires the production orchestration and both entry ports; catalog and planning I/O are substituted. */
class SupervisorRunReplayExecutorTest {
    private final RuntimeAgentExecutionContextResolver resolver = mock(RuntimeAgentExecutionContextResolver.class);
    private final SupervisorRuntimeAdapter planner = mock(SupervisorRuntimeAdapter.class);
    private final AtomicReference<SupervisorRuntimeAdapter.SupervisorRequest> captured = new AtomicReference<>();
    private AnnotationConfigApplicationContext app;

    @BeforeEach
    void wireProductionEntries() {
        var agent = new RuntimeAgentExecutionView("agent-1", 7L, "orders", "orders-bot", "Orders", null,
                "PROJECT", null, false, 99L, null, null);
        var archived = RuntimeAgentConfigSnapshot.builder().id(11L).agentId("agent-1").versionNo(3)
                .status("ARCHIVED").runtimeType("AGENTSCOPE").build();
        when(resolver.resolvePublished("agent-1", 11L)).thenReturn(Optional.of(new RuntimeAgentExecutionContext(
                agent, archived, List.of(), List.of(), null)));
        when(planner.execute(any())).thenAnswer(call -> {
            SupervisorRuntimeAdapter.SupervisorRequest request = call.getArgument(0);
            captured.set(request);
            request.eventSink().emit("message.delta", Map.of("text", "回复"));
            return new SupervisorRuntimeAdapter.SupervisorResult(true, "OK", "原版本结果", "replay-trace",
                    List.of(Map.of("nodeId", "archived-node")), Map.of(), null);
        });
        app = new AnnotationConfigApplicationContext();
        app.registerBean(RuntimeAgentExecutionContextResolver.class, () -> resolver);
        app.registerBean(SupervisorRuntimeAdapter.class, () -> planner);
        app.registerBean(RuntimeSupervisorApprovalPort.class, () -> mock(RuntimeSupervisorApprovalPort.class));
        app.registerBean(RuntimeInteractionResumeService.class, () -> mock(RuntimeInteractionResumeService.class));
        app.registerBean(RuntimeSessionClearPort.class, () -> mock(RuntimeSessionClearPort.class));
        app.registerBean(RuntimeAgentRunLifecyclePort.class, () -> mock(RuntimeAgentRunLifecyclePort.class));
        app.register(RuntimeAgentExecutionService.class, SupervisorRunReplayExecutor.class);
        app.refresh();
    }

    @AfterEach
    void close() { if (app != null) app.close(); }

    @Test
    void runOpsPortUsesThePinnedArchivedConfigInsteadOfTheCurrentActiveVersion() {
        var result = app.getBean(RuntimeRunReplayExecutionPort.class).executePublishedAgent("agent-1", 11L,
                Map.of("message", "替代输入", "agentId", "body-decoy", "replayOfTraceId", "original-trace"));
        assertTrue(Boolean.TRUE.equals(result.get("success")));
        assertEquals("原版本结果", result.get("answer"));
        assertEquals(11L, captured.get().config().getId());
        assertEquals("ARCHIVED", captured.get().config().getStatus());
        assertEquals("agent-1", captured.get().input().get("agentId"));
        assertEquals("替代输入", captured.get().input().get("message"));
        assertEquals("original-trace", captured.get().input().get("replayOfTraceId"));
        assertEquals(11L, ((Map<?, ?>) result.get("metadata")).get("agentConfigVersionId"));
        verify(resolver).resolvePublished("agent-1", 11L);
        verify(resolver, never()).resolve(anyString());
    }

    @Test
    void protocolPortPreservesTrustedIdentityCancellationAndStreamingEvents() {
        var execution = app.getBean(RuntimePublishedAgentExecutionPort.class);
        assertSame(app.getBean(RuntimeAgentExecutionService.class), execution);
        var identity = WorkflowExecutionIdentity.fromEmbedSession(7L, "orders", "user-42");
        var cancellation = new RuntimeAgentExecutionCancellation();
        var events = new ArrayList<String>();
        execution.executePublishedConfig("agent-1", 11L, Map.of("message", "hello"), true,
                (event, data) -> events.add(event), cancellation, identity);
        assertSame(identity, captured.get().identity());
        assertSame(cancellation, captured.get().cancellation());
        assertEquals(List.of("message.delta"), events);
        cancellation.cancel();
        assertTrue(captured.get().cancellation().isCancelled());
        verify(resolver, never()).resolve(anyString());
    }

    @Test
    void aMissingPinnedVersionDoesNotFallBackToActivePlanning() {
        when(resolver.resolvePublished("agent-1", 12L)).thenReturn(Optional.empty());
        var result = app.getBean(RuntimeRunReplayExecutionPort.class).executePublishedAgent("agent-1", 12L,
                Map.of("message", "替代输入"));
        assertFalse(Boolean.TRUE.equals(result.get("success")));
        verify(resolver).resolvePublished("agent-1", 12L);
        verify(resolver, never()).resolve(anyString());
        verifyNoInteractions(planner);
    }
}
