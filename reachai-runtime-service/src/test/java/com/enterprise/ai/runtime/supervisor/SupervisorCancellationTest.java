package com.enterprise.ai.runtime.supervisor;

import com.enterprise.ai.runtime.agent.RuntimeAgentConfigVersionEntity;
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
                        SupervisorRuntimeAdapter.SupervisorEventSink.NOOP,
                        cancellation));

        assertFalse(result.success());
        assertEquals("SUPERVISOR_CANCELLED", result.code());
        assertEquals(0, modelCalls.get(), "cancelled run must not start model or forced PUBLIC_FINAL");
    }

    @Test
    void enterPublicFinalReturnsFalseWhenAlreadyCancelled() {
        SupervisorAnswerPhase phase = new SupervisorAnswerPhase();
        phase.markCancelled();
        assertFalse(phase.enterPublicFinal());
        assertEquals(SupervisorAnswerPhase.Phase.CANCELLED, phase.get());
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
                        SupervisorRuntimeAdapter.SupervisorEventSink.NOOP,
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
                traceService, mock(SupervisorApprovalInteractionService.class), objectMapper);
        return new AgentScopeSupervisorRuntimeAdapter(
                modelClient,
                null,
                mock(RuntimeWorkflowDefinitionMapper.class),
                mock(RuntimeWorkflowVersionMapper.class),
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

    private RuntimeAgentConfigVersionEntity config() {
        RuntimeAgentConfigVersionEntity config = new RuntimeAgentConfigVersionEntity();
        config.setId(7L);
        config.setAgentId("agent-1");
        config.setVersionNo(1);
        config.setRuntimeType("AGENTSCOPE");
        config.setSystemPrompt("你是班组助手");
        config.setModelInstanceId("model-1");
        config.setMaxPlanSteps(6);
        config.setMaxWorkflowCalls(4);
        config.setMaxReplans(1);
        config.setTotalTimeoutMs(10_000);
        config.setWorkflowTimeoutMs(5_000);
        config.setPageBridgeTimeoutMs(2_000);
        config.setParallelReadOnly(false);
        config.setPolicyProfile("DEV_ALLOW_ALL");
        config.setToolCatalogMode("ALLOW_LIST");
        config.setConfigJson("{}");
        return config;
    }
}
