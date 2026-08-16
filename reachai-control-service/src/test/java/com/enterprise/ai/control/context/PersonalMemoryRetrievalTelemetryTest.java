package com.enterprise.ai.control.context;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PersonalMemoryRetrievalTelemetryTest {

    @Test
    void recordsLowCardinalityShadowQualityAndSafetyMetrics() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        PersonalMemoryRetrievalTelemetry telemetry = new PersonalMemoryRetrievalTelemetry(registry);
        PersonalMemoryKnowledgeQueryClient.QueryAttempt attempt =
                new PersonalMemoryKnowledgeQueryClient.QueryAttempt(
                        PersonalMemoryKnowledgeQueryMode.SHADOW, true, true,
                        List.of(2L, 3L), 2_000_000L, "SUCCESS");

        telemetry.record(attempt, List.of(1L, 2L), Set.of(1L, 2L), 2);

        assertEquals(1.0d, registry.get("reachai.personal_memory.retrieval.requests")
                .tags("provider", "knowledge", "mode", "shadow", "outcome", "success")
                .counter().count());
        assertEquals(1L, registry.get("reachai.personal_memory.retrieval.latency")
                .tags("provider", "knowledge", "mode", "shadow", "outcome", "success")
                .timer().count());
        assertEquals(0.5d, registry.get("reachai.personal_memory.retrieval.canonical_coverage")
                .tags("provider", "knowledge", "mode", "shadow")
                .summary().totalAmount());
        assertEquals(0.5d, registry.get("reachai.personal_memory.retrieval.projected_precision")
                .tags("provider", "knowledge", "mode", "shadow")
                .summary().totalAmount());
        assertEquals(0.5d, registry.get("reachai.personal_memory.retrieval.invalid_id_ratio")
                .tags("provider", "knowledge", "mode", "shadow")
                .summary().totalAmount());
    }
}
