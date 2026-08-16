package com.enterprise.ai.runtime.memory;

import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "reachai.runtime.session-retention", name = "enabled",
        havingValue = "true", matchIfMissing = false)
public class RuntimeSessionRetentionScheduler {

    private static final Logger log = LoggerFactory.getLogger(RuntimeSessionRetentionScheduler.class);
    private static final String METRIC_PREFIX = "reachai.runtime.session_retention";

    private final RuntimeSessionRetentionService retentionService;
    private final MeterRegistry meterRegistry;

    public RuntimeSessionRetentionScheduler(
            RuntimeSessionRetentionService retentionService,
            MeterRegistry meterRegistry) {
        this.retentionService = retentionService;
        this.meterRegistry = meterRegistry;
    }

    @Scheduled(
            fixedDelayString = "${reachai.runtime.session-retention.cleanup-fixed-delay-ms:300000}",
            initialDelayString = "${reachai.runtime.session-retention.cleanup-initial-delay-ms:60000}")
    public void cleanupDueSessions() {
        try {
            RuntimeSessionRetentionService.CleanupResult result = retentionService.cleanupDue();
            meterRegistry.counter(METRIC_PREFIX + ".runs", "outcome", "success").increment();
            meterRegistry.summary(METRIC_PREFIX + ".purged").record(result.purged());
            meterRegistry.summary(METRIC_PREFIX + ".recovered_clears")
                    .record(result.recoveredClears());
            if (result.notCompleted() > 0) {
                meterRegistry.counter(METRIC_PREFIX + ".not_completed")
                        .increment(result.notCompleted());
            }
            if (result.purged() > 0 || result.recoveredClears() > 0) {
                log.info("Runtime session retention purged {} session(s) and recovered {} clear(s)",
                        result.purged(), result.recoveredClears());
            }
        } catch (RuntimeException failure) {
            meterRegistry.counter(METRIC_PREFIX + ".runs", "outcome", "error").increment();
            log.warn("Runtime session retention run failed: {}", failure.getClass().getSimpleName());
        }
    }
}
