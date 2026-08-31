package com.enterprise.ai.runtime.managed;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "reachai.runtime.managed-executor.enabled", havingValue = "true")
public class ManagedExecutionCleanupScheduler {

    private final ManagedExecutionService executionService;
    private final ManagedSandboxProvisioner provisioner;

    @Scheduled(
            initialDelayString = "${reachai.runtime.managed-executor.cleanup-initial-delay-ms:10000}",
            fixedDelayString = "${reachai.runtime.managed-executor.cleanup-fixed-delay-ms:5000}")
    public void cleanup() {
        if (!provisioner.available()) return;
        for (int index = 0; index < 20; index++) {
            ManagedExecutionService.CleanupReservation reservation = executionService.reserveCleanup();
            if (reservation == null) return;
            if (reservation.sandboxRef() == null || reservation.sandboxRef().isBlank()) {
                executionService.completeCleanup(reservation);
                continue;
            }
            try {
                provisioner.cleanup(new ManagedSandboxProvisioner.CleanupRequest(
                        reservation.executionId(), reservation.sandboxRef()));
                executionService.completeCleanup(reservation);
            } catch (Exception failure) {
                executionService.failCleanup(reservation, "Managed Sandbox cleanup failed");
            }
        }
    }
}
