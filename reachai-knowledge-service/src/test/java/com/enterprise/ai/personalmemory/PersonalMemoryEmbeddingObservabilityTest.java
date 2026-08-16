package com.enterprise.ai.personalmemory;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PersonalMemoryEmbeddingObservabilityTest {

    @Test
    void publishesAggregateReadinessWithoutIdentityLabels() {
        Fixture fixture = fixture(PersonalMemoryEmbeddingProperties.Mode.HYBRID);
        PersonalMemoryEmbeddingStatusRow row = new PersonalMemoryEmbeddingStatusRow();
        row.setActiveCount(100L);
        row.setReadyCount(99L);
        row.setDeadCount(0L);
        row.setOldestPendingSeconds(12L);
        row.setUnsafeDeletedVectorCount(0L);
        when(fixture.mapper.selectEmbeddingStatus(any(), anyString(), anyString())).thenReturn(row);

        fixture.observability.refresh();

        var snapshot = fixture.observability.snapshot();
        assertTrue(snapshot.enabled());
        assertTrue(snapshot.fresh());
        assertEquals(.99d, snapshot.readyRatio(), 0.000001);
        assertEquals(12L, snapshot.oldestPendingSeconds());
        assertEquals(.99d, fixture.registry.get(metric("ready_ratio")).gauge().value(), 0.000001);
        assertTrue(fixture.registry.getMeters().stream()
                .flatMap(meter -> meter.getId().getTags().stream())
                .noneMatch(tag -> tag.getKey().matches("tenant|user|owner|memoryId")));
    }

    @Test
    void refreshFailureMarksSnapshotStaleWithoutDiscardingLastKnownCounts() {
        Fixture fixture = fixture(PersonalMemoryEmbeddingProperties.Mode.HYBRID);
        PersonalMemoryEmbeddingStatusRow row = new PersonalMemoryEmbeddingStatusRow();
        row.setActiveCount(2L);
        row.setReadyCount(2L);
        when(fixture.mapper.selectEmbeddingStatus(any(), anyString(), anyString()))
                .thenReturn(row)
                .thenThrow(new IllegalStateException("database unavailable"));
        fixture.observability.refresh();

        fixture.observability.refresh();

        var snapshot = fixture.observability.snapshot();
        assertFalse(snapshot.fresh());
        assertEquals(2L, snapshot.activeCount());
        assertEquals(1d, fixture.registry.get(metric("refresh"))
                .tag("outcome", "error").counter().count(), 0.000001);
    }

    @Test
    void lexicalModeDoesNotScanTheProjectionTable() {
        Fixture fixture = fixture(PersonalMemoryEmbeddingProperties.Mode.LEXICAL);

        fixture.observability.refresh();

        assertFalse(fixture.observability.snapshot().enabled());
        assertTrue(fixture.observability.snapshot().fresh());
        verify(fixture.mapper, never()).selectEmbeddingStatus(any(), anyString(), anyString());
    }

    private static String metric(String suffix) {
        return "reachai.personal_memory.embedding.projection." + suffix;
    }

    private static Fixture fixture(PersonalMemoryEmbeddingProperties.Mode mode) {
        KnowledgePersonalMemoryIndexMapper mapper = mock(KnowledgePersonalMemoryIndexMapper.class);
        PersonalMemoryEmbeddingProperties properties = new PersonalMemoryEmbeddingProperties();
        properties.setMode(mode);
        if (mode != PersonalMemoryEmbeddingProperties.Mode.LEXICAL) {
            properties.setModelInstanceId("memory-embedding");
        }
        properties.afterPropertiesSet();
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        PersonalMemoryEmbeddingObservability observability =
                new PersonalMemoryEmbeddingObservability(mapper, properties, registry);
        return new Fixture(observability, mapper, registry);
    }

    private record Fixture(PersonalMemoryEmbeddingObservability observability,
                           KnowledgePersonalMemoryIndexMapper mapper,
                           SimpleMeterRegistry registry) {
    }
}
