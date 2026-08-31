package com.enterprise.ai.runtime.managed;

public interface ManagedSandboxProvisioner {

    boolean available();

    SandboxHandle provision(ProvisioningRequest request) throws Exception;

    void cleanup(CleanupRequest request) throws Exception;

    record ProvisioningRequest(
            String executionId,
            String workerBootstrapToken,
            String tenantId,
            String projectCode,
            String sourceType,
            String sourceRef,
            String executorProvider,
            String sandboxProfile,
            String acceptanceProfile,
            int maxWallTimeSeconds) {

        @Override
        public String toString() {
            return "ProvisioningRequest[executionId=" + executionId
                    + ", workerBootstrapToken=<redacted>"
                    + ", tenantId=" + tenantId
                    + ", projectCode=" + projectCode
                    + ", sourceType=" + sourceType
                    + ", sourceRef=" + sourceRef
                    + ", executorProvider=" + executorProvider
                    + ", sandboxProfile=" + sandboxProfile
                    + ", acceptanceProfile=" + acceptanceProfile
                    + ", maxWallTimeSeconds=" + maxWallTimeSeconds + "]";
        }
    }

    record SandboxHandle(String sandboxRef) {
        public SandboxHandle {
            if (sandboxRef == null || !sandboxRef.matches("[a-z0-9][a-z0-9.-]{0,127}")) {
                throw new IllegalArgumentException("Managed Sandbox reference is invalid");
            }
        }
    }

    record CleanupRequest(String executionId, String sandboxRef) {
    }
}
