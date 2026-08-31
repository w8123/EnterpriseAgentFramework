package com.enterprise.ai.capability.internal;

import com.enterprise.ai.common.capability.CapabilityInvocationFailureCategory;

/** Explicit fail-closed policy or catalog rejection; never drives retry. */
public final class CapabilityInvocationPolicyException extends RuntimeException {

    private final String code;
    private final CapabilityInvocationFailureCategory category;

    public CapabilityInvocationPolicyException(String code,
                                               CapabilityInvocationFailureCategory category,
                                               String message) {
        super(message);
        this.code = code;
        this.category = category;
    }

    public String code() {
        return code;
    }

    public CapabilityInvocationFailureCategory category() {
        return category;
    }
}
