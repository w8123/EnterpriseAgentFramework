package com.enterprise.ai.control.managed;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Retries durable Runtime-to-Control projections, including start-binding races. */
@Component
@ConditionalOnProperty(
        prefix = "reachai.control.managed-executor",
        name = "projection-enabled",
        havingValue = "true",
        matchIfMissing = true)
@RequiredArgsConstructor
public class ControlManagedExecutionProjectionScheduler {

    private final ControlManagedExecutionInboxService inboxService;

    @Scheduled(fixedDelayString =
            "${reachai.control.managed-executor.projection-delay-ms:5000}")
    public void projectPending() {
        for (String eventId : inboxService.projectionCandidates(50)) {
            try {
                inboxService.projectOne(eventId);
            } catch (RuntimeException ignored) {
                // The lease expires and the durable inbox entry is retried.
            }
        }
    }
}
