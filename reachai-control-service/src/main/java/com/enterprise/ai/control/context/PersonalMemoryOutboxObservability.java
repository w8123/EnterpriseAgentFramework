package com.enterprise.ai.control.context;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDateTime;

/** Publishes aggregate-only canonical-to-Knowledge outbox health without identity or payload labels. */
@Component
@ConditionalOnProperty(prefix = "reachai.context.personal-memory", name = "outbox-enabled",
        havingValue = "true", matchIfMissing = true)
public class PersonalMemoryOutboxObservability {

    private static final Logger log = LoggerFactory.getLogger(PersonalMemoryOutboxObservability.class);
    private static final String METRIC_PREFIX = "reachai.personal_memory.outbox";

    private final ContextMemoryOutboxMapper mapper;
    private final MeterRegistry meterRegistry;
    private volatile Snapshot snapshot = Snapshot.uninitialized();

    public PersonalMemoryOutboxObservability(ContextMemoryOutboxMapper mapper, MeterRegistry meterRegistry) {
        this.mapper = mapper;
        this.meterRegistry = meterRegistry;
        registerGauges();
    }

    @Scheduled(
            fixedDelayString = "${reachai.context.personal-memory.outbox-observability-delay-ms:30000}",
            initialDelayString = "${reachai.context.personal-memory.outbox-observability-initial-delay-ms:10000}")
    public void refresh() {
        try {
            PersonalMemoryOutboxStatusRow row = mapper.selectPersonalMemoryStatus(LocalDateTime.now());
            if (row == null) {
                throw new IllegalStateException("personal-memory outbox status query returned no row");
            }
            snapshot = new Snapshot(
                    true,
                    nonNegative(row.getBacklogCount()),
                    nonNegative(row.getDeadCount()),
                    nonNegative(row.getOldestUnpublishedSeconds()),
                    Instant.now().getEpochSecond());
            meterRegistry.counter(METRIC_PREFIX + ".refresh", "outcome", "success").increment();
        } catch (RuntimeException failure) {
            snapshot = snapshot.withFresh(false);
            meterRegistry.counter(METRIC_PREFIX + ".refresh", "outcome", "error").increment();
            log.warn("Personal memory outbox metrics refresh failed: failureType={}",
                    failure.getClass().getSimpleName());
        }
    }

    Snapshot snapshot() {
        return snapshot;
    }

    private void registerGauges() {
        gauge("snapshot_fresh", value -> value.snapshot.fresh() ? 1 : 0);
        gauge("backlog", value -> value.snapshot.backlogCount());
        gauge("dead", value -> value.snapshot.deadCount());
        gauge("oldest_unpublished_seconds", value -> value.snapshot.oldestUnpublishedSeconds());
        gauge("last_success_epoch_seconds", value -> value.snapshot.lastSuccessEpochSeconds());
    }

    private void gauge(String suffix,
                       java.util.function.ToDoubleFunction<PersonalMemoryOutboxObservability> value) {
        Gauge.builder(METRIC_PREFIX + "." + suffix, this, value).register(meterRegistry);
    }

    private static long nonNegative(Long value) {
        return value == null ? 0 : Math.max(0, value);
    }

    record Snapshot(boolean fresh,
                    long backlogCount,
                    long deadCount,
                    long oldestUnpublishedSeconds,
                    long lastSuccessEpochSeconds) {

        static Snapshot uninitialized() {
            return new Snapshot(false, 0, 0, 0, 0);
        }

        Snapshot withFresh(boolean value) {
            return new Snapshot(value, backlogCount, deadCount, oldestUnpublishedSeconds,
                    lastSuccessEpochSeconds);
        }
    }
}
