package com.enterprise.ai.capability.internal;

/** Immutable execution descriptor resolved from the Capability catalog or Kernel asset store. */
public record CapabilityInvocationAsset(
        Source source,
        String qualifiedName,
        String name,
        String title,
        String capabilityCode,
        String executorType,
        String sideEffect,
        boolean enabled
) {

    public enum Source {
        TOOL_CATALOG,
        KERNEL_ASSET
    }
}
