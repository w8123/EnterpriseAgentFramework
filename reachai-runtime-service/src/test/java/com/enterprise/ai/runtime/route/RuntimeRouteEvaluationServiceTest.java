package com.enterprise.ai.runtime.route;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.enterprise.ai.runtime.trace.RuntimeToolCallLogEntity;
import com.enterprise.ai.runtime.trace.RuntimeToolCallLogMapper;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RuntimeRouteEvaluationServiceTest {

    @Test
    void evaluatesRouteReadinessFromRuntimeToolLogs() {
        RuntimeToolCallLogMapper toolLogMapper = mock(RuntimeToolCallLogMapper.class);
        RuntimeRouteEvaluationService service = new RuntimeRouteEvaluationService(toolLogMapper);
        when(toolLogMapper.selectList(org.mockito.ArgumentMatchers.<Wrapper<RuntimeToolCallLogEntity>>any()))
                .thenReturn(List.of(
                        log(1L, "trace-1", "GENERAL_CHAT", "Agent A", null),
                        log(2L, "trace-1", "GENERAL_CHAT", "Agent A", "[{\"docId\":\"doc-1\"}]"),
                        log(3L, "trace-2", "ORDER_QA", "Agent B", null)
                ));

        RuntimeRouteEvaluationView view = service.evaluate(30);

        assertEquals(30, view.days());
        assertEquals(3, view.logCount());
        assertEquals(2L, view.traceCount());
        assertEquals(1L, view.retrievalTraceCount());
        assertEquals(Map.of("GENERAL_CHAT", 2L, "ORDER_QA", 1L), view.intentCounts());
        assertEquals(Map.of("Agent A", 2L, "Agent B", 1L), view.agentCounts());
        assertFalse(view.intentClassifierReady());
        assertFalse(view.domainClassifierReady());
    }

    private RuntimeToolCallLogEntity log(Long id,
                                         String traceId,
                                         String intentType,
                                         String agentName,
                                         String retrievalTraceJson) {
        RuntimeToolCallLogEntity entity = new RuntimeToolCallLogEntity();
        entity.setId(id);
        entity.setTraceId(traceId);
        entity.setIntentType(intentType);
        entity.setAgentName(agentName);
        entity.setRetrievalTraceJson(retrievalTraceJson);
        entity.setCreateTime(LocalDateTime.parse("2026-06-29T12:00:00"));
        return entity;
    }
}
