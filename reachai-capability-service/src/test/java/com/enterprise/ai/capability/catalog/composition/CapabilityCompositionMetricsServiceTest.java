package com.enterprise.ai.capability.catalog.composition;

import com.enterprise.ai.capability.client.runtime.CapabilityRuntimeTraceClient;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CapabilityCompositionMetricsServiceTest {

    private final CapabilityRuntimeTraceClient traceClient = mock(CapabilityRuntimeTraceClient.class);
    private final CapabilityCompositionMetricsService service = new CapabilityCompositionMetricsService(traceClient);

    @Test
    void returnsEmptyTrendForCompositionWithoutLogs() {
        when(traceClient.listToolCallsByTool("order_form", 3)).thenReturn(List.of());

        CapabilityCompositionMetricsService.CompositionMetricsView result = service.metrics("order_form", 3);

        assertEquals(0, result.callCount());
        assertEquals(0D, result.successRate());
        assertEquals(3, result.trends().size());
        assertEquals(0, result.trends().get(0).callCount());
        verify(traceClient).listToolCallsByTool("order_form", 3);
    }

    @Test
    void aggregatesCompositionMetricsFromToolCallLog() {
        when(traceClient.listToolCallsByTool("order_form", 7)).thenReturn(List.of(
                log(1L, "order_form", true, 10, 4, LocalDateTime.of(2026, 6, 30, 9, 0)),
                log(2L, "order_form", false, 30, 15, LocalDateTime.of(2026, 6, 30, 10, 0)),
                log(3L, "order_form", true, 20, 8, LocalDateTime.of(2026, 7, 1, 9, 0))
        ));

        CapabilityCompositionMetricsService.CompositionMetricsView result = service.metrics("order_form", 7);

        assertEquals(3, result.callCount());
        assertEquals(2D / 3D, result.successRate());
        assertEquals(20, result.p50LatencyMs());
        assertEquals(30, result.p95LatencyMs());
        assertEquals(8, result.p50TokenCost());
        assertEquals(15, result.p95TokenCost());
        assertEquals(2, result.trends().size());
        assertEquals("2026-06-30", result.trends().get(0).day());
        assertEquals(2, result.trends().get(0).callCount());
        assertEquals(0.5D, result.trends().get(0).successRate());
    }

    private CapabilityRuntimeTraceClient.ToolCallLogRecord log(Long id,
                                                               String toolName,
                                                               boolean success,
                                                               int elapsedMs,
                                                               int tokenCost,
                                                               LocalDateTime createTime) {
        return new CapabilityRuntimeTraceClient.ToolCallLogRecord(
                id,
                "trace-" + id,
                "session",
                "user",
                "agent",
                "intent",
                toolName,
                "{}",
                "{}",
                success,
                null,
                elapsedMs,
                tokenCost,
                createTime
        );
    }
}
