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
public class ManagedExecutionProvisioningScheduler {

    private final ManagedExecutionService executionService;
    private final ManagedSandboxProvisioner provisioner;

    @Scheduled(
            initialDelayString = "${reachai.runtime.managed-executor.provision-initial-delay-ms:5000}",
            fixedDelayString = "${reachai.runtime.managed-executor.provision-fixed-delay-ms:1000}")
    public void provision() {
        if (!provisioner.available()) return;
        executionService.recoverExpiredProvisioningTokens();
        for (int i = 0; i < 10; i++) {
            ManagedExecutionService.ProvisioningReservation reservation = executionService.reserveProvisioning();
            if (reservation == null) return;
            try {
                ManagedSandboxProvisioner.SandboxHandle handle = provisioner.provision(reservation.request());
                executionService.markProvisionDispatched(reservation, handle);
            } catch (ManagedSandboxProvisionOutcomeUnknownException unknown) {
                executionService.markProvisionOutcomeUnknown(reservation, unknown.handle());
            } catch (Exception failure) {
                executionService.releaseProvisioning(reservation, safeMessage(failure));
            }
        }
    }

    private String safeMessage(Throwable failure) {
        String value = failure == null || failure.getMessage() == null
                ? "Managed Sandbox provision failed" : failure.getMessage();
        return value.length() <= 500 ? value : value.substring(0, 500);
    }
}
