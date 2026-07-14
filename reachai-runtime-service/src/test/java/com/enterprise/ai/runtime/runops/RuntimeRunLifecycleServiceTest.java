package com.enterprise.ai.runtime.runops;

import com.enterprise.ai.runtime.agent.RuntimeAgentConfigVersionEntity;
import com.enterprise.ai.runtime.agent.RuntimeAgentView;
import com.enterprise.ai.runtime.trace.RuntimeToolCallLogMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RuntimeRunLifecycleServiceTest {

    @Test
    void persistsDirectAnswerAgentRunWithoutToolEvents() {
        LifecycleFixture fixture = fixture();
        LocalDateTime startedAt = LocalDateTime.of(2026, 7, 14, 9, 30, 0);
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("entryType", "EMBED");
        input.put("projectCode", "orders");
        input.put("sessionId", "session-before");
        input.put("userId", "user-before");
        input.put("message", "直接回答这个问题");

        fixture.service().beginAgent(
                "trace-direct",
                "span-root",
                startedAt,
                agent(),
                publishedConfig(),
                List.of(),
                input);

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("sessionId", "session-final");
        metadata.put("userId", "user-final");
        metadata.put("planCount", 1);
        metadata.put("replanCount", 0);
        metadata.put("workflowCallCount", 0);
        metadata.put("usage", Map.of("inputTokens", 12, "outputTokens", 8));
        fixture.service().finishAgent(
                "trace-direct",
                true,
                "SUPERVISOR_COMPLETED",
                "这是无需调用 Workflow 的直接回答",
                metadata,
                startedAt.plusNanos(1_500_000_000L));

        RuntimeRunEntity saved = fixture.saved().get();
        assertNotNull(saved);
        assertEquals("trace-direct", saved.getTraceId());
        assertEquals("AGENT", saved.getRunType());
        assertEquals("EMBED", saved.getEntryType());
        assertEquals("SUCCESS", saved.getStatus());
        assertEquals(7L, saved.getProjectId());
        assertEquals("orders", saved.getProjectCode());
        assertEquals("agent-1", saved.getAgentId());
        assertEquals(91L, saved.getAgentConfigVersionId());
        assertEquals(4, saved.getAgentConfigVersion());
        assertEquals("session-final", saved.getSessionId());
        assertEquals("user-final", saved.getUserId());
        assertEquals("这是无需调用 Workflow 的直接回答", saved.getOutputSummary());
        assertEquals(1, saved.getPlanCount());
        assertEquals(0, saved.getReplanCount());
        assertEquals(0, saved.getWorkflowCallCount());
        assertEquals(0, saved.getToolCallCount());
        assertEquals(0, saved.getGuardDenyCount());
        assertEquals(0, saved.getApprovalCount());
        assertEquals(20, saved.getTokenCost());
        assertEquals(1500, saved.getLatencyMs());
        assertNull(saved.getErrorCode());
        assertTrue(saved.getSnapshotJson().contains("\"workflowTools\":[]"));
        verify(fixture.runMapper()).insert(saved);
        verify(fixture.runMapper()).updateById(saved);
    }

    @Test
    void mapsSupervisorConfirmationToWaitingApprovalRootStatus() {
        LifecycleFixture fixture = fixture();
        when(fixture.guardMapper().selectCount(any())).thenReturn(0L, 1L);
        LocalDateTime startedAt = LocalDateTime.of(2026, 7, 14, 9, 31, 0);

        fixture.service().beginAgent(
                "trace-waiting",
                "span-root",
                startedAt,
                agent(),
                publishedConfig(),
                List.of(),
                Map.of("message", "请执行高风险操作", "sessionId", "session-approval"));
        fixture.service().finishAgent(
                "trace-waiting",
                false,
                "SUPERVISOR_CONFIRMATION_REQUIRED",
                "需要人工确认后继续",
                Map.of("planCount", 1, "workflowCallCount", 0),
                startedAt.plusSeconds(2));

        RuntimeRunEntity saved = fixture.saved().get();
        assertEquals("WAITING_APPROVAL", saved.getStatus());
        assertEquals("SUPERVISOR_CONFIRMATION_REQUIRED", saved.getErrorCode());
        assertEquals("需要人工确认后继续", saved.getErrorMessage());
        assertEquals(1, saved.getApprovalCount());
        assertEquals(0, saved.getWorkflowCallCount());
        assertEquals(0, saved.getToolCallCount());
        assertNotNull(saved.getEndedAt());
    }

    private LifecycleFixture fixture() {
        RuntimeRunMapper runMapper = mock(RuntimeRunMapper.class);
        RuntimeToolCallLogMapper toolCallLogMapper = mock(RuntimeToolCallLogMapper.class);
        RuntimeGuardDecisionLogMapper guardMapper = mock(RuntimeGuardDecisionLogMapper.class);
        AtomicReference<RuntimeRunEntity> saved = new AtomicReference<>();
        when(runMapper.selectOne(any())).thenAnswer(invocation -> saved.get());
        when(runMapper.insert(any())).thenAnswer(invocation -> {
            RuntimeRunEntity entity = invocation.getArgument(0);
            entity.setId(1L);
            saved.set(entity);
            return 1;
        });
        when(toolCallLogMapper.selectCount(any())).thenReturn(0L);
        when(guardMapper.selectCount(any())).thenReturn(0L);
        RuntimeRunLifecycleService service = new RuntimeRunLifecycleService(
                runMapper, toolCallLogMapper, guardMapper, new ObjectMapper());
        return new LifecycleFixture(service, runMapper, guardMapper, saved);
    }

    private RuntimeAgentView agent() {
        return new RuntimeAgentView(
                "agent-1", 7L, "orders", "orders-agent", "Orders Agent", null,
                "PROJECT", null, true,
                91L, 91L, 4, "ARCHIVED", "AGENTSCOPE", 0, null, null);
    }

    private RuntimeAgentConfigVersionEntity publishedConfig() {
        RuntimeAgentConfigVersionEntity config = new RuntimeAgentConfigVersionEntity();
        config.setId(91L);
        config.setAgentId("agent-1");
        config.setVersionNo(4);
        config.setStatus("ARCHIVED");
        config.setRuntimeType("AGENTSCOPE");
        return config;
    }

    private record LifecycleFixture(
            RuntimeRunLifecycleService service,
            RuntimeRunMapper runMapper,
            RuntimeGuardDecisionLogMapper guardMapper,
            AtomicReference<RuntimeRunEntity> saved) {
    }
}
