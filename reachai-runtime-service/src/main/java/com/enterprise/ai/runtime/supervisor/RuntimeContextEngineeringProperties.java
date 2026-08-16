package com.enterprise.ai.runtime.supervisor;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Runtime-owned context engineering controls. The master switch is off by default so the
 * existing Supervisor, streaming and interaction semantics remain unchanged until canary rollout.
 */
@ConfigurationProperties(prefix = "reachai.runtime.context-engineering")
public record RuntimeContextEngineeringProperties(
        boolean enabled,
        boolean compactionEnabled,
        boolean overflowRecoveryEnabled,
        int compactionTriggerMessages,
        int compactionTriggerTokens,
        int compactionKeepMessages,
        int emergencyKeepMessages,
        long compactionTimeoutMs,
        boolean toolResultOffloadEnabled,
        int toolResultMaxChars,
        int toolResultPreviewChars,
        int artifactMaxBytes,
        int artifactReadChunkChars,
        int artifactRetentionHours,
        int artifactCleanupBatchSize,
        long artifactCleanupFixedDelayMs,
        String artifactEncryptionKeyId,
        String artifactEncryptionSecret) {

    public RuntimeContextEngineeringProperties {
        compactionTriggerMessages = clamp(compactionTriggerMessages, 8, 160);
        compactionTriggerTokens = clamp(compactionTriggerTokens, 4_000, 1_000_000);
        compactionKeepMessages = clamp(
                compactionKeepMessages, 2, Math.max(2, compactionTriggerMessages - 2));
        emergencyKeepMessages = clamp(
                emergencyKeepMessages, 2, Math.max(2, compactionTriggerMessages - 2));
        compactionTimeoutMs = clamp(compactionTimeoutMs, 5_000L, 120_000L);
        toolResultMaxChars = clamp(toolResultMaxChars, 4_000, 2_000_000);
        toolResultPreviewChars = clamp(
                toolResultPreviewChars, 200, Math.max(200, toolResultMaxChars / 4));
        artifactMaxBytes = clamp(artifactMaxBytes, 1_000_000, 128_000_000);
        artifactReadChunkChars = clamp(
                artifactReadChunkChars, 1_000, Math.min(100_000, toolResultMaxChars));
        artifactRetentionHours = clamp(artifactRetentionHours, 1, 24 * 30);
        artifactCleanupBatchSize = clamp(artifactCleanupBatchSize, 10, 2_000);
        artifactCleanupFixedDelayMs = clamp(
                artifactCleanupFixedDelayMs, 10_000L, 3_600_000L);
        artifactEncryptionKeyId = textOrDefault(artifactEncryptionKeyId, "primary");
        artifactEncryptionSecret = artifactEncryptionSecret == null
                ? ""
                : artifactEncryptionSecret.trim();
    }

    public boolean compactionActive() {
        return enabled && compactionEnabled;
    }

    public boolean overflowRecoveryActive() {
        return enabled && overflowRecoveryEnabled;
    }

    public boolean toolResultOffloadActive() {
        return enabled && toolResultOffloadEnabled;
    }

    /**
     * The offload flag also declares that the artifact table and key have been provisioned.
     * Cleanup may remain active while the master canary switch is temporarily disabled.
     */
    public boolean toolResultArtifactStoreConfigured() {
        return toolResultOffloadEnabled;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(value, max));
    }

    private static long clamp(long value, long min, long max) {
        return Math.max(min, Math.min(value, max));
    }

    private static String textOrDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
