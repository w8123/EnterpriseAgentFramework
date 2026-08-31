package com.enterprise.ai.runtime.managed;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "reachai.runtime.managed-executor", name = "sandbox-backend",
        havingValue = "disabled", matchIfMissing = true)
public class DisabledManagedSandboxProvisioner implements ManagedSandboxProvisioner {

    @Override
    public boolean available() {
        return false;
    }

    @Override
    public SandboxHandle provision(ProvisioningRequest request) {
        throw new IllegalStateException("Managed Sandbox provisioner is not configured");
    }

    @Override
    public void cleanup(CleanupRequest request) {
        throw new IllegalStateException("Managed Sandbox provisioner is not configured");
    }
}
