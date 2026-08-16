package com.enterprise.ai.control.context;

import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Scheduled privacy erasure for canonical personal memories with expires_at. */
@Component
public class PersonalMemoryLifecycleScheduler {

    private static final Logger log = LoggerFactory.getLogger(PersonalMemoryLifecycleScheduler.class);
    private static final String METRIC_PREFIX = "reachai.personal_memory.lifecycle";
    private final PersonalMemoryService memoryService;
    private final MeterRegistry meterRegistry;
    private final boolean enabled;
    private final int batchSize;

    public PersonalMemoryLifecycleScheduler(
            PersonalMemoryService memoryService,
            MeterRegistry meterRegistry,
            @Value("${reachai.context.personal-memory.lifecycle-enabled:true}") boolean enabled,
            @Value("${reachai.context.personal-memory.lifecycle-batch-size:100}") int batchSize) {
        this.memoryService = memoryService;
        this.meterRegistry = meterRegistry;
        this.enabled = enabled;
        this.batchSize = Math.max(1, Math.min(batchSize, 500));
    }

    @Scheduled(
            fixedDelayString = "${reachai.context.personal-memory.lifecycle-delay-ms:60000}",
            initialDelayString = "${reachai.context.personal-memory.lifecycle-initial-delay-ms:60000}")
    public void expireDue() {
        if (!enabled) return;
        try {
            int expired = memoryService.expireDue(batchSize);
            meterRegistry.counter(METRIC_PREFIX + ".runs", "outcome", "success").increment();
            meterRegistry.summary(METRIC_PREFIX + ".expired").record(expired);
        } catch (RuntimeException ex) {
            meterRegistry.counter(METRIC_PREFIX + ".runs", "outcome", "error").increment();
            log.warn("Personal memory lifecycle run failed: {}", ex.getClass().getSimpleName());
        }
    }
}
