package com.enterprise.ai.control.context;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Opt-in worker. It stays disabled until the orchestration migration is installed. */
@Component
@ConditionalOnProperty(prefix = "reachai.context.personal-memory",
        name = "erasure-worker-enabled", havingValue = "true")
public class MemoryErasureWorker {

    private final MemoryErasureOrchestrationService service;
    private final int batchSize;

    public MemoryErasureWorker(
            MemoryErasureOrchestrationService service,
            @Value("${reachai.context.personal-memory.erasure-worker-batch-size:10}")
            int batchSize) {
        this.service = service;
        this.batchSize = Math.max(1, Math.min(batchSize, 50));
    }

    @Scheduled(
            fixedDelayString = "${reachai.context.personal-memory.erasure-worker-delay-ms:5000}",
            initialDelayString = "${reachai.context.personal-memory.erasure-worker-initial-delay-ms:15000}")
    public void process() {
        service.processDue(batchSize);
    }
}
