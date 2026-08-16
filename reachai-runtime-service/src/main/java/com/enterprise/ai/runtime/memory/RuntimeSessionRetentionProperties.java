package com.enterprise.ai.runtime.memory;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Runtime-owned defaults for tenant session retention and lifecycle leases. */
@ConfigurationProperties(prefix = "reachai.runtime.session-retention")
public record RuntimeSessionRetentionProperties(
        boolean enabled,
        int defaultActiveRetentionDays,
        int defaultClearedRetentionHours,
        int cleanupBatchSize,
        int lifecycleLeaseSeconds) {

    public RuntimeSessionRetentionProperties {
        requireRange("default-active-retention-days", defaultActiveRetentionDays, 1, 3_650);
        requireRange("default-cleared-retention-hours", defaultClearedRetentionHours, 1, 87_600);
        requireRange("cleanup-batch-size", cleanupBatchSize, 1, 500);
        requireRange("lifecycle-lease-seconds", lifecycleLeaseSeconds, 30, 3_600);
    }

    private static void requireRange(String name, int value, int minimum, int maximum) {
        if (value < minimum || value > maximum) {
            throw new IllegalArgumentException(
                    "reachai.runtime.session-retention." + name
                            + " must be between " + minimum + " and " + maximum);
        }
    }
}
