package com.enterprise.ai.runtime.trace.usage;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RuntimeModelUsageRecorderTest {

    private final RuntimeModelUsageMapper mapper = mock(RuntimeModelUsageMapper.class);
    private final RuntimeModelUsageRecorder recorder = new RuntimeModelUsageRecorder(mapper);

    @Test
    void writesOneRowPerCall() {
        recorder.record(" trace-1 ", RuntimeModelUsageRecorder.SOURCE_WORKFLOW_NODE, "mi-1", 100, 20);

        ArgumentCaptor<RuntimeModelUsageEntity> row = ArgumentCaptor.forClass(RuntimeModelUsageEntity.class);
        verify(mapper).insert(row.capture());
        assertEquals("trace-1", row.getValue().getTraceId());
        assertEquals("WORKFLOW_NODE", row.getValue().getUsageSource());
        assertEquals(100, row.getValue().getInputTokens());
        assertEquals(20, row.getValue().getOutputTokens());
    }

    @Test
    void outsideARunKeepsNoTraceAndSkipsEmptyUsage() {
        recorder.record(null, null, "mi-1", 5, null);
        ArgumentCaptor<RuntimeModelUsageEntity> row = ArgumentCaptor.forClass(RuntimeModelUsageEntity.class);
        verify(mapper).insert(row.capture());
        assertNull(row.getValue().getTraceId());
        assertEquals("AGENT", row.getValue().getUsageSource());

        recorder.record("t", "AGENT", "mi-1", 0, 0);
        recorder.record("t", "AGENT", " ", 3, 3);
        verify(mapper).insert(any(RuntimeModelUsageEntity.class));
    }

    @Test
    void databaseErrorsNeverBreakTheRun() {
        doThrow(new IllegalStateException("no table")).when(mapper).insert(any(RuntimeModelUsageEntity.class));
        recorder.record("t", "AGENT", "mi-1", 1, 1);
    }

    @Test
    void usageClampsDaysAndTrimsProject() {
        when(mapper.selectUsage(any(LocalDateTime.class), eq("yudao-erp"))).thenReturn(List.of(new RuntimeModelUsageRow()));
        assertEquals(1, recorder.usage(" yudao-erp ", 500).size());
        verify(mapper, never()).selectUsage(any(LocalDateTime.class), eq(" yudao-erp "));
    }
}
