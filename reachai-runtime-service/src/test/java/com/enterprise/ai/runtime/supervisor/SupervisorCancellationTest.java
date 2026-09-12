package com.enterprise.ai.runtime.supervisor;

import com.enterprise.ai.runtime.execution.RuntimeAgentExecutionEventSink;
import com.enterprise.ai.runtime.agentscope.AgentScopeAnswerPhase;
import com.enterprise.ai.runtime.agent.RuntimeAgentConfigSnapshot;
import com.enterprise.ai.runtime.agent.RuntimeAgentView;
import com.enterprise.ai.runtime.chat.RuntimeChatMemoryStore;
import com.enterprise.ai.runtime.client.model.RuntimeModelServiceClient;
import com.enterprise.ai.runtime.client.model.RuntimeModelServiceClient.ModelChatData;
import com.enterprise.ai.runtime.client.model.RuntimeModelServiceClient.ModelChatResult;
import com.enterprise.ai.runtime.client.model.RuntimeModelServiceClient.ModelUsage;
import com.enterprise.ai.runtime.execution.RuntimeAgentExecutionCancellation;
import com.enterprise.ai.runtime.execution.RuntimeGraphSpecExecutor;
import com.enterprise.ai.runtime.workflow.RuntimeWorkflowDefinitionMapper;
import com.enterprise.ai.runtime.workflow.RuntimeWorkflowVersionMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 复现并锁定：INTERNAL 取消后不得启动 forced PUBLIC_FINAL 第二次模型请求。
 */
class SupervisorCancellationTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void cancelBeforeExecuteDoesNotStartForcedFinalPass() {
        AtomicInteger modelCalls = new AtomicInteger();
        RuntimeModelServiceClient modelClient = request -> {
            int n = modelCalls.incrementAndGet();
            throw new AssertionError("cancelled run must not call model; call#" + n);
        };

        RuntimeAgentExecutionCancellation cancellation = new RuntimeAgentExecutionCancellation();
        cancellation.cancel();
        AgentScopeSupervisorRuntimeAdapter adapter = adapter(modelClient);

        SupervisorRuntimeAdapter.SupervisorResult result = adapter.execute(
                new SupervisorRuntimeAdapter.SupervisorRequest(
                        agent(),
                        config(),
                        List.of(),
                        Map.of("message", "你好", "sessionId", "s-cancel", "projectCode", "qmssmp"),
                        null,
                        RuntimeAgentExecutionEventSink.NOOP,
                        cancellation));

        assertFalse(result.success());
        assertEquals("SUPERVISOR_CANCELLED", result.code());
        assertEquals(0, modelCalls.get(), "cancelled run must not start model or forced PUBLIC_FINAL");
    }

    @Test
    void enterPublicFinalReturnsFalseWhenAlreadyCancelled() {
        AgentScopeAnswerPhase phase = new AgentScopeAnswerPhase();
        phase.markCancelled();
        assertFalse(phase.enterPublicFinal());
        assertEquals(AgentScopeAnswerPhase.Phase.CANCELLED, phase.get());
    }

    @Test
    void cancelDuringInternalStreamDoesNotStartSecondModelRequest() {
        AtomicInteger modelCalls = new AtomicInteger();
        RuntimeAgentExecutionCancellation cancellation = new RuntimeAgentExecutionCancellation();
        RuntimeModelServiceClient modelClient = request -> {
            int n = modelCalls.incrementAndGet();
            if (n == 1) {
                // 模拟 INTERNAL 阶段客户端断开：取消后不得再有 forced PUBLIC_FINAL
                cancellation.cancel();
                return new ModelChatResult(200, "success",
                        new ModelChatData("你好", "test", "test", new ModelUsage(1, 1, 2),
                                null, null, "stop"));
            }
            throw new AssertionError("cancelled run must not start second model request; call#" + n);
        };

        AgentScopeSupervisorRuntimeAdapter adapter = adapter(modelClient);
        SupervisorRuntimeAdapter.SupervisorResult result = adapter.execute(
                new SupervisorRuntimeAdapter.SupervisorRequest(
                        agent(),
                        config(),
                        List.of(),
                        Map.of("message", "你好", "sessionId", "s-cancel-mid", "projectCode", "qmssmp"),
                        null,
                        RuntimeAgentExecutionEventSink.NOOP,
                        cancellation));

        assertFalse(result.success());
        assertEquals("SUPERVISOR_CANCELLED", result.code());
        assertTrue(modelCalls.get() <= 1, "must not force PUBLIC_FINAL second pass, calls=" + modelCalls.get());
    }

    private AgentScopeSupervisorRuntimeAdapter adapter(RuntimeModelServiceClient modelClient) {
        SupervisorExecutionTraceService traceService = mock(SupervisorExecutionTraceService.class);
        SupervisorExecutionTraceService.TraceHandle handle =
                new SupervisorExecutionTraceService.TraceHandle("trace-1", "span-1", 1L, LocalDateTime.now());
        when(traceService.begin(any(), any(), any(), any())).thenReturn(handle);
        when(traceService.begin(any(), any(), any(), any(), any())).thenReturn(handle);
        when(traceService.beginOrResume(any(), any(), any(), any())).thenReturn(handle);
        when(traceService.beginOrResume(any(), any(), any(), any(), any())).thenReturn(handle);
        SupervisorToolPolicyService policy = new SupervisorToolPolicyService(
                org.mockito.Mockito.mock(com.enterprise.ai.runtime.runops.RuntimeGuardDecisionWriter.class), mock(SupervisorApprovalInteractionService.class), objectMapper);
        return new AgentScopeSupervisorRuntimeAdapter(
                modelClient,
                null,
                null,
                mock(com.enterprise.ai.runtime.workflow.RuntimeWorkflowExecutionQuery.class),
                mock(RuntimeGraphSpecExecutor.class),
                mock(com.enterprise.ai.runtime.execution.RuntimeWorkflowInteractionSessionService.class),
                new RuntimeChatMemoryStore(20),
                policy,
                traceService,
                objectMapper);
    }

    private RuntimeAgentView agent() {
        return new RuntimeAgentView(
                "agent-1", 7L, "qmssmp", "team-assistant", "班组助手", null,
                "PROJECT", null, true,
                7L, 7L, 1, "ACTIVE", "AGENTSCOPE", 2, null, null);
    }

    private RuntimeAgentConfigSnapshot config() {
        RuntimeAgentConfigSnapshot config = RuntimeAgentConfigSnapshot.builder()
                .id(7L)
                .agentId("agent-1")
                .versionNo(1)
                .runtimeType("AGENTSCOPE")
                .systemPrompt("你是班组助手")
                .modelInstanceId("model-1")
                .maxPlanSteps(6)
                .maxWorkflowCalls(4)
                .maxReplans(1)
                .totalTimeoutMs(10_000)
                .workflowTimeoutMs(5_000)
                .pageBridgeTimeoutMs(2_000)
                .parallelReadOnly(false)
                .policyProfile("DEV_ALLOW_ALL")
                .toolCatalogMode("ALLOW_LIST")
                .configJson("{}")
                .build();
        return config;
    }
}
