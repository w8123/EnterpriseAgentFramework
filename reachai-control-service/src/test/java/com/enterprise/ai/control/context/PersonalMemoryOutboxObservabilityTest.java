package com.enterprise.ai.control.context;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PersonalMemoryOutboxObservabilityTest {

    @Test
    void publishesAggregateOnlyBacklogAndDeadLetterState() {
        ContextMemoryOutboxMapper mapper = mock(ContextMemoryOutboxMapper.class);
        PersonalMemoryOutboxStatusRow row = new PersonalMemoryOutboxStatusRow();
        row.setBacklogCount(3L);
        row.setDeadCount(0L);
        row.setOldestUnpublishedSeconds(12L);
        when(mapper.selectPersonalMemoryStatus(any())).thenReturn(row);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        PersonalMemoryOutboxObservability observability =
                new PersonalMemoryOutboxObservability(mapper, registry);

        observability.refresh();

        assertTrue(observability.snapshot().fresh());
        assertEquals(3L, observability.snapshot().backlogCount());
        assertEquals(12d, registry.get(metric("oldest_unpublished_seconds")).gauge().value());
        assertTrue(registry.getMeters().stream()
                .flatMap(meter -> meter.getId().getTags().stream())
                .noneMatch(tag -> tag.getKey().matches("tenant|user|owner|memoryId|eventId")));
    }

    @Test
    void refreshFailureMarksTheLastSnapshotStaleWithoutDiscardingCounts() {
        ContextMemoryOutboxMapper mapper = mock(ContextMemoryOutboxMapper.class);
        PersonalMemoryOutboxStatusRow row = new PersonalMemoryOutboxStatusRow();
        row.setBacklogCount(2L);
        when(mapper.selectPersonalMemoryStatus(any()))
                .thenReturn(row)
                .thenThrow(new IllegalStateException("database unavailable"));
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        PersonalMemoryOutboxObservability observability =
                new PersonalMemoryOutboxObservability(mapper, registry);
        observability.refresh();

        observability.refresh();

        assertFalse(observability.snapshot().fresh());
        assertEquals(2L, observability.snapshot().backlogCount());
        assertEquals(1d, registry.get(metric("refresh"))
                .tag("outcome", "error").counter().count());
    }

    private static String metric(String suffix) {
        return "reachai.personal_memory.outbox." + suffix;
    }
}
