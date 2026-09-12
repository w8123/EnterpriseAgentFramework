package com.enterprise.ai.runtime.supervisor;

import com.enterprise.ai.runtime.agent.RuntimeAgentConfigSnapshot;
import com.enterprise.ai.runtime.agent.RuntimeAgentView;
import com.enterprise.ai.runtime.identity.WorkflowExecutionIdentity;
import com.enterprise.ai.runtime.runops.RuntimeRunLifecycleService;
import com.enterprise.ai.runtime.trace.RuntimeToolCallLogEntity;
import com.enterprise.ai.runtime.trace.RuntimeToolCallLogMapper;
import com.enterprise.ai.runtime.trace.RuntimeTraceSpanMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SupervisorExecutionTraceAuditIdentityTest {

    @Test
    void toolCallLogUserIdUsesTrustedIdentityNotBodyAttacker() {
        RuntimeToolCallLogMapper toolLogMapper = mock(RuntimeToolCallLogMapper.class);
        RuntimeTraceSpanMapper spanMapper = mock(RuntimeTraceSpanMapper.class);
        when(spanMapper.insert(any())).thenReturn(1);
        when(toolLogMapper.insert(any())).thenReturn(1);
        RuntimeRunLifecycleService lifecycle = mock(RuntimeRunLifecycleService.class);
        SupervisorExecutionTraceService service = new SupervisorExecutionTraceService(
                new com.enterprise.ai.runtime.trace.RuntimeTraceEvidenceWriter(spanMapper, toolLogMapper),
                lifecycle,
                new ObjectMapper(),
                new com.enterprise.ai.runtime.trace.RuntimeTraceRootService(spanMapper, new ObjectMapper()),
                new com.enterprise.ai.runtime.trace.RuntimeTraceSpanTerminationService(spanMapper));

        SupervisorExecutionTraceService.TraceHandle trace =
                new SupervisorExecutionTraceService.TraceHandle("trace-1", "span-root", 9L,
                        java.time.LocalDateTime.of(2026, 7, 19, 12, 0));
        WorkflowExecutionIdentity identity = WorkflowExecutionIdentity.fromAgent(7L, "orders", "42");
        Map<String, Object> input = Map.of("userId", "attacker", "sessionId", "s1");

        service.workflow(trace, agent(), publishedConfig(), input,
                "wf_tool", "wf-1", 11L, "3",
                Map.of("message", "x"), true, "OK", "done", 12L, Map.of(), identity);

        ArgumentCaptor<RuntimeToolCallLogEntity> captor = ArgumentCaptor.forClass(RuntimeToolCallLogEntity.class);
        verify(toolLogMapper).insert(captor.capture());
        assertEquals("42", captor.getValue().getUserId());
        assertEquals("42", captor.getValue().getExternalUserId());
        assertEquals("42", captor.getValue().getGlobalUserId());
    }

    @Test
    void toolCallLogUserIdStaysNullWithoutTrustedIdentity() {
        RuntimeToolCallLogMapper toolLogMapper = mock(RuntimeToolCallLogMapper.class);
        RuntimeTraceSpanMapper spanMapper = mock(RuntimeTraceSpanMapper.class);
        when(spanMapper.insert(any())).thenReturn(1);
        when(toolLogMapper.insert(any())).thenReturn(1);
        SupervisorExecutionTraceService service = new SupervisorExecutionTraceService(
                new com.enterprise.ai.runtime.trace.RuntimeTraceEvidenceWriter(spanMapper, toolLogMapper),
                mock(RuntimeRunLifecycleService.class),
                new ObjectMapper(),
                new com.enterprise.ai.runtime.trace.RuntimeTraceRootService(spanMapper, new ObjectMapper()),
                new com.enterprise.ai.runtime.trace.RuntimeTraceSpanTerminationService(spanMapper));

        SupervisorExecutionTraceService.TraceHandle trace =
                new SupervisorExecutionTraceService.TraceHandle("trace-2", "span-root", 9L,
                        java.time.LocalDateTime.of(2026, 7, 19, 12, 1));

        service.workflow(trace, agent(), publishedConfig(), Map.of("userId", "42"),
                "wf_tool", "wf-1", 11L, "3",
                Map.of(), true, "OK", "done", 12L, Map.of(), null);

        ArgumentCaptor<RuntimeToolCallLogEntity> captor = ArgumentCaptor.forClass(RuntimeToolCallLogEntity.class);
        verify(toolLogMapper).insert(captor.capture());
        assertNull(captor.getValue().getUserId());
    }

    @Test
    void embedIdentityWinsOverBodyAttackerForToolCallLog() {
        RuntimeToolCallLogMapper toolLogMapper = mock(RuntimeToolCallLogMapper.class);
        RuntimeTraceSpanMapper spanMapper = mock(RuntimeTraceSpanMapper.class);
        when(spanMapper.insert(any())).thenReturn(1);
        when(toolLogMapper.insert(any())).thenReturn(1);
        SupervisorExecutionTraceService service = new SupervisorExecutionTraceService(
                new com.enterprise.ai.runtime.trace.RuntimeTraceEvidenceWriter(spanMapper, toolLogMapper),
                mock(RuntimeRunLifecycleService.class),
                new ObjectMapper(),
                new com.enterprise.ai.runtime.trace.RuntimeTraceRootService(spanMapper, new ObjectMapper()),
                new com.enterprise.ai.runtime.trace.RuntimeTraceSpanTerminationService(spanMapper));

        SupervisorExecutionTraceService.TraceHandle trace =
                new SupervisorExecutionTraceService.TraceHandle("trace-3", "span-root", 9L,
                        java.time.LocalDateTime.of(2026, 7, 19, 12, 2));
        WorkflowExecutionIdentity identity =
                WorkflowExecutionIdentity.fromEmbedSession(7L, "orders", "embed-user");

        service.workflow(trace, agent(), publishedConfig(), Map.of("userId", "attacker"),
                "wf_tool", "wf-1", 11L, "3",
                Map.of(), true, "OK", "done", 12L, Map.of(), identity);

        ArgumentCaptor<RuntimeToolCallLogEntity> captor = ArgumentCaptor.forClass(RuntimeToolCallLogEntity.class);
        verify(toolLogMapper).insert(captor.capture());
        assertEquals("embed-user", captor.getValue().getUserId());
    }

    private RuntimeAgentView agent() {
        return new RuntimeAgentView(
                "agent-1", 7L, "orders", "orders-agent", "Orders Agent", null,
                "PROJECT", null, true,
                91L, 91L, 4, "ARCHIVED", "AGENTSCOPE", 0, null, null);
    }

    private RuntimeAgentConfigSnapshot publishedConfig() {
        RuntimeAgentConfigSnapshot config = RuntimeAgentConfigSnapshot.builder()
                .id(91L)
                .agentId("agent-1")
                .versionNo(4)
                .status("ARCHIVED")
                .runtimeType("AGENTSCOPE")
                .policyProfile("DEFAULT")
                .toolCatalogMode("PUBLISHED")
                .build();
        return config;
    }
}
