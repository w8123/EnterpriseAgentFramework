package com.enterprise.ai.runtime.managed;

public class ManagedSandboxProvisionOutcomeUnknownException extends Exception {

    private final ManagedSandboxProvisioner.SandboxHandle handle;

    public ManagedSandboxProvisionOutcomeUnknownException(ManagedSandboxProvisioner.SandboxHandle handle) {
        super("Managed Sandbox provisioning outcome is unknown");
        this.handle = handle;
    }

    public ManagedSandboxProvisioner.SandboxHandle handle() {
        return handle;
    }
}
