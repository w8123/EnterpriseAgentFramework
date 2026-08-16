package com.enterprise.ai.personalmemory;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDateTime;

/** Publishes aggregate-only semantic projection readiness without PII/high-cardinality labels. */
@Component
public class PersonalMemoryEmbeddingObservability {

    private static final Logger log = LoggerFactory.getLogger(PersonalMemoryEmbeddingObservability.class);
    private static final String METRIC_PREFIX = "reachai.personal_memory.embedding.projection";

    private final KnowledgePersonalMemoryIndexMapper mapper;
    private final PersonalMemoryEmbeddingProperties properties;
    private final MeterRegistry meterRegistry;
    private volatile Snapshot snapshot = Snapshot.uninitialized();

    public PersonalMemoryEmbeddingObservability(KnowledgePersonalMemoryIndexMapper mapper,
                                                PersonalMemoryEmbeddingProperties properties,
                                                MeterRegistry meterRegistry) {
        this.mapper = mapper;
        this.properties = properties;
        this.meterRegistry = meterRegistry;
        registerGauges();
    }

    @Scheduled(
            fixedDelayString = "${reachai.personal-memory.embedding.observability-fixed-delay-ms:30000}",
            initialDelayString = "${reachai.personal-memory.embedding.observability-initial-delay-ms:10000}")
    public void refresh() {
        if (!properties.semanticEnabled()) {
            snapshot = Snapshot.disabled(Instant.now().getEpochSecond());
            return;
        }
        try {
            PersonalMemoryEmbeddingStatusRow row = mapper.selectEmbeddingStatus(
                    LocalDateTime.now(), properties.getModelInstanceId(), PersonalMemoryEmbeddingCodec.FORMAT);
            if (row == null) {
                throw new IllegalStateException("personal-memory embedding status query returned no row");
            }
            long active = nonNegative(row.getActiveCount());
            long ready = nonNegative(row.getReadyCount());
            snapshot = new Snapshot(
                    true,
                    true,
                    active,
                    ready,
                    active == 0 ? 0 : ready / (double) active,
                    nonNegative(row.getDeadCount()),
                    nonNegative(row.getOldestPendingSeconds()),
                    nonNegative(row.getUnsafeDeletedVectorCount()),
                    Instant.now().getEpochSecond());
            meterRegistry.counter(METRIC_PREFIX + ".refresh", "outcome", "success").increment();
        } catch (RuntimeException failure) {
            Snapshot previous = snapshot;
            snapshot = previous.withFresh(false);
            meterRegistry.counter(METRIC_PREFIX + ".refresh", "outcome", "error").increment();
            log.warn("Personal memory embedding projection metrics refresh failed: failureType={}",
                    failure.getClass().getSimpleName());
        }
    }

    Snapshot snapshot() {
        return snapshot;
    }

    private void registerGauges() {
        gauge("enabled", value -> value.properties.semanticEnabled() ? 1 : 0);
        gauge("snapshot_fresh", value -> value.snapshot.fresh() ? 1 : 0);
        gauge("active", value -> value.snapshot.activeCount());
        gauge("ready", value -> value.snapshot.readyCount());
        gauge("ready_ratio", value -> value.snapshot.readyRatio());
        gauge("dead", value -> value.snapshot.deadCount());
        gauge("oldest_pending_seconds", value -> value.snapshot.oldestPendingSeconds());
        gauge("unsafe_deleted_vectors", value -> value.snapshot.unsafeDeletedVectorCount());
        gauge("last_success_epoch_seconds", value -> value.snapshot.lastSuccessEpochSeconds());
    }

    private void gauge(String suffix,
                       java.util.function.ToDoubleFunction<PersonalMemoryEmbeddingObservability> value) {
        Gauge.builder(METRIC_PREFIX + "." + suffix, this, value).register(meterRegistry);
    }

    private static long nonNegative(Long value) {
        return value == null ? 0 : Math.max(0, value);
    }

    record Snapshot(boolean enabled,
                    boolean fresh,
                    long activeCount,
                    long readyCount,
                    double readyRatio,
                    long deadCount,
                    long oldestPendingSeconds,
                    long unsafeDeletedVectorCount,
                    long lastSuccessEpochSeconds) {

        static Snapshot uninitialized() {
            return new Snapshot(false, false, 0, 0, 0, 0, 0, 0, 0);
        }

        static Snapshot disabled(long observedAt) {
            return new Snapshot(false, true, 0, 0, 0, 0, 0, 0, observedAt);
        }

        Snapshot withFresh(boolean value) {
            return new Snapshot(enabled, value, activeCount, readyCount, readyRatio,
                    deadCount, oldestPendingSeconds, unsafeDeletedVectorCount,
                    lastSuccessEpochSeconds);
        }
    }
}
