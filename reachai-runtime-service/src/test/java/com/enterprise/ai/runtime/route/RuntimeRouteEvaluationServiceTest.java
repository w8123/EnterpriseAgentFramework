package com.enterprise.ai.runtime.route;

import com.enterprise.ai.runtime.trace.RuntimeToolUsageStatisticsQuery;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RuntimeRouteEvaluationServiceTest {

    @Test
    void evaluatesRouteReadinessFromRuntimeToolLogs() {
        RuntimeToolUsageStatisticsQuery statistics = mock(RuntimeToolUsageStatisticsQuery.class);
        RuntimeRouteEvaluationService service = new RuntimeRouteEvaluationService(statistics);
        when(statistics.summarizeSince(org.mockito.ArgumentMatchers.any()))
                .thenReturn(new RuntimeToolUsageStatisticsQuery.Summary(3, 2, 1,
                        Map.of("GENERAL_CHAT", 2L, "ORDER_QA", 1L), Map.of("Agent A", 2L, "Agent B", 1L)));

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

}
