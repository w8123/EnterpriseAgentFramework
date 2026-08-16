package com.enterprise.ai.runtime.memory;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "reachai.runtime.session-memory")
public record RuntimeSessionMemoryProperties(
        boolean enabled,
        String stateStore,
        String jsonDirectory,
        String redisUri,
        String redisKeyPrefix,
        boolean requireRedisInProduction,
        int maxContextMessages,
        int maxStoredContentChars,
        int turnLeaseSeconds) {

    public RuntimeSessionMemoryProperties {
        stateStore = textOrDefault(stateStore, "json");
        jsonDirectory = textOrDefault(jsonDirectory, "${user.home}/.reachai/runtime-session-state");
        redisUri = textOrDefault(redisUri, "redis://localhost:6379");
        redisKeyPrefix = textOrDefault(redisKeyPrefix, "reachai:runtime:session-state:");
        maxContextMessages = Math.max(4, Math.min(maxContextMessages, 200));
        maxStoredContentChars = Math.max(1_000, Math.min(maxStoredContentChars, 1_000_000));
        turnLeaseSeconds = Math.max(30, Math.min(turnLeaseSeconds, 3_600));
    }

    private static String textOrDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
