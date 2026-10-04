package com.enterprise.ai.capability.internal;

import com.enterprise.ai.common.capability.CapabilityInvocationFailureCategory;

import java.util.Map;

/** Explicit fail-closed policy or catalog rejection; never drives retry. */
public final class CapabilityInvocationPolicyException extends RuntimeException {

    private final String code;
    private final CapabilityInvocationFailureCategory category;
    private final Map<String, Object> safeMetadata;

    public CapabilityInvocationPolicyException(String code,
                                               CapabilityInvocationFailureCategory category,
                                               String message) {
        this(code, category, message, Map.of());
    }

    public CapabilityInvocationPolicyException(String code,
                                               CapabilityInvocationFailureCategory category,
                                               String message,
                                               Map<String, Object> safeMetadata) {
        super(message);
        this.code = code;
        this.category = category;
        this.safeMetadata = safeMetadata == null || safeMetadata.isEmpty()
                ? Map.of() : Map.copyOf(safeMetadata);
    }

    public String code() {
        return code;
    }

    public CapabilityInvocationFailureCategory category() {
        return category;
    }

    public Map<String, Object> safeMetadata() {
        return safeMetadata;
    }
}
