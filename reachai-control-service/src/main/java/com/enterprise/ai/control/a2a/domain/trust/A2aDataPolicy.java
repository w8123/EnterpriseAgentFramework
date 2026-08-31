package com.enterprise.ai.control.a2a.domain.trust;

import com.enterprise.ai.control.a2a.domain.A2aDomainException;

import java.util.Set;

public record A2aDataPolicy(
        Set<String> allowedDataClassifications,
        boolean allowTextParts,
        boolean allowFileParts,
        boolean allowUrlParts,
        int payloadRetentionDays) {

    public A2aDataPolicy {
        allowedDataClassifications = normalized(allowedDataClassifications);
        if (allowedDataClassifications.isEmpty()) {
            throw new A2aDomainException("A2A_DATA_CLASSIFICATION_REQUIRED",
                    "at least one allowed data classification is required");
        }
        if (!allowTextParts && !allowFileParts && !allowUrlParts) {
            throw new A2aDomainException("A2A_PART_TYPE_REQUIRED",
                    "at least one A2A Part type must be allowed");
        }
        if (payloadRetentionDays < 0 || payloadRetentionDays > 3650) {
            throw new A2aDomainException("A2A_RETENTION_INVALID",
                    "payloadRetentionDays must be between 0 and 3650");
        }
    }

    private static Set<String> normalized(Set<String> values) {
        if (values == null || values.isEmpty()) {
            return Set.of();
        }
        return values.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(value -> value.trim().toUpperCase(java.util.Locale.ROOT))
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }
}
