package com.enterprise.ai.capability.internal;

/** Immutable execution descriptor derived from an accepted business-method asset. */
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
        BUSINESS_METHOD
    }
}
