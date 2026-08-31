package com.enterprise.ai.runtime.managed;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "reachai.runtime.managed-executor.enabled", havingValue = "true")
public class ManagedExecutionLeaseExpiryScheduler {

    private final ManagedExecutionService executionService;

    @Scheduled(
            initialDelayString = "${reachai.runtime.managed-executor.lease-expiry-initial-delay-ms:10000}",
            fixedDelayString = "${reachai.runtime.managed-executor.lease-expiry-fixed-delay-ms:5000}")
    public void expireLeases() {
        try {
            int expired = executionService.expireLeases(100);
            if (expired > 0) log.warn("Marked {} Managed Executor lease(s) timed out", expired);
        } catch (Exception failure) {
            log.error("Managed Executor lease expiry scan failed: {}", safeMessage(failure));
        }
    }

    private String safeMessage(Throwable failure) {
        String value = failure == null || failure.getMessage() == null
                ? "lease expiry failed" : failure.getMessage();
        return value.length() <= 500 ? value : value.substring(0, 500);
    }
}
