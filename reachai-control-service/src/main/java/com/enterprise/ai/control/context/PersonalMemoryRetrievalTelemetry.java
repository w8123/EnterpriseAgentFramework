package com.enterprise.ai.control.context;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Low-cardinality rollout telemetry. Tenant, user, query text, and memory ids
 * are deliberately excluded from metric tags.
 */
@Component
public class PersonalMemoryRetrievalTelemetry {

    private static final String PREFIX = "reachai.personal_memory.retrieval";
    private final MeterRegistry meterRegistry;

    public PersonalMemoryRetrievalTelemetry(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    public void record(PersonalMemoryKnowledgeQueryClient.QueryAttempt attempt,
                       List<Long> canonicalSelectedIds,
                       Set<Long> canonicalCandidateIds,
                       int topK) {
        String mode = attempt.mode().name().toLowerCase();
        String outcome = attempt.outcome().toLowerCase();
        meterRegistry.counter(PREFIX + ".requests",
                "provider", "knowledge", "mode", mode, "outcome", outcome).increment();
        if (attempt.attempted()) {
            meterRegistry.timer(PREFIX + ".latency",
                    "provider", "knowledge", "mode", mode, "outcome", outcome)
                    .record(Duration.ofNanos(Math.max(0L, attempt.elapsedNanos())));
        }
        meterRegistry.summary(PREFIX + ".returned_ids",
                "provider", "knowledge", "mode", mode).record(attempt.ids().size());
        if (!attempt.successful() || attempt.mode() == PersonalMemoryKnowledgeQueryMode.OFF) {
            return;
        }

        List<Long> projected = attempt.ids().stream().limit(Math.max(1, topK)).toList();
        List<Long> canonical = canonicalSelectedIds.stream().limit(Math.max(1, topK)).toList();
        Set<Long> projectedSet = new HashSet<>(projected);
        long overlap = canonical.stream().filter(projectedSet::contains).count();
        double canonicalCoverage = canonical.isEmpty()
                ? (projected.isEmpty() ? 1.0d : 0.0d)
                : (double) overlap / canonical.size();
        double projectedPrecision = projected.isEmpty()
                ? (canonical.isEmpty() ? 1.0d : 0.0d)
                : (double) overlap / projected.size();
        long invalid = projected.stream().filter(id -> !canonicalCandidateIds.contains(id)).count();
        double invalidRatio = projected.isEmpty() ? 0.0d : (double) invalid / projected.size();

        if (attempt.mode() == PersonalMemoryKnowledgeQueryMode.SHADOW) {
            recordRatio("canonical_coverage", mode, canonicalCoverage);
            recordRatio("projected_precision", mode, projectedPrecision);
        }
        recordRatio("invalid_id_ratio", mode, invalidRatio);
    }

    private void recordRatio(String metric, String mode, double value) {
        meterRegistry.summary(PREFIX + "." + metric,
                "provider", "knowledge", "mode", mode)
                .record(Math.max(0.0d, Math.min(1.0d, value)));
    }
}
