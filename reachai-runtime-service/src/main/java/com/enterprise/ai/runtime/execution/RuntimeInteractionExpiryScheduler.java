package com.enterprise.ai.runtime.execution;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "reachai.runtime.interaction-expiry", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class RuntimeInteractionExpiryScheduler {

    private final RuntimeInteractionExpiryProcessor processor;

    @Value("${reachai.runtime.interaction-expiry.batch-size:100}")
    private int batchSize;

    @Scheduled(
            fixedDelayString = "${reachai.runtime.interaction-expiry.fixed-delay-ms:5000}",
            initialDelayString = "${reachai.runtime.interaction-expiry.initial-delay-ms:5000}")
    public void reconcileExpiredInteractions() {
        LocalDateTime now = LocalDateTime.now();
        List<RuntimeInteractionSessionEntity> candidates = processor.findExpiredCandidates(now, batchSize);
        int expired = 0;
        for (RuntimeInteractionSessionEntity candidate : candidates) {
            try {
                if (processor.expireOne(candidate, now)) {
                    expired++;
                }
            } catch (Exception ex) {
                log.warn("Failed to expire interaction session {}: {}", candidate.getId(), ex.getMessage());
            }
        }
        if (expired > 0) {
            log.info("Expired {} runtime interaction session(s)", expired);
        }
    }
}
