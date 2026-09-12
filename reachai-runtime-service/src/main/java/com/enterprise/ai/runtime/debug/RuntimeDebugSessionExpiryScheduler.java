package com.enterprise.ai.runtime.debug;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Reconciles abandoned execution attempts through the same owner used by interactive reads. */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "reachai.runtime.debug-session-expiry", name = "enabled", havingValue = "true", matchIfMissing = true)
public class RuntimeDebugSessionExpiryScheduler {
    private final RuntimeDebugSessionStore store;

    @Value("${reachai.runtime.debug-session-expiry.batch-size:100}")
    private int batchSize = 100;

    @Scheduled(fixedDelayString = "${reachai.runtime.debug-session-expiry.fixed-delay-ms:5000}",
            initialDelayString = "${reachai.runtime.debug-session-expiry.initial-delay-ms:5000}")
    public void reconcileExpiredAttempts() {
        for (String id : store.findExpiredCandidateIds(batchSize)) {
            try {
                store.require(id);
            } catch (RuntimeException failure) {
                log.warn("Cannot reconcile debug session {}: {}", id, failure.getClass().getSimpleName());
            }
        }
    }
}
