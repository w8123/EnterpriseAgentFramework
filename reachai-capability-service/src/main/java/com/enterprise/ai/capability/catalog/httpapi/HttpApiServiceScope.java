package com.enterprise.ai.capability.catalog.httpapi;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.Locale;

/**
 * Immutable Capability-owned service scope for an HTTP API operation.
 *
 * <p>The scope deliberately contains no base URL or credential reference. Those are environment
 * configuration facts, not logical operation identity.</p>
 */
public record HttpApiServiceScope(Long projectId, String projectCode, String environment, String externalServiceKey) {

    public HttpApiServiceScope(Long projectId, String projectCode, String environment) {
        this(projectId, projectCode, environment, null);
    }

    public HttpApiServiceScope {
        if (projectId == null || projectId <= 0) {
            throw new IllegalArgumentException("HTTP API projectId must be positive");
        }
        projectCode = normalizeProjectCode(projectCode);
        environment = normalizeEnvironment(environment);
        if (externalServiceKey != null && !externalServiceKey.matches("api-market:[a-z0-9][a-z0-9._-]{0,159}")) {
            throw new IllegalArgumentException("HTTP API external service key is invalid");
        }
    }

    public String qualifiedNamePrefix() {
        return "http-api:" + projectCode + ":" + environment + ":" + (externalServiceKey == null ? "" : "market:");
    }

    /**
     * Stable external scope used by operation identity and contract pins. The database surrogate
     * project ID remains an internal isolation key and must not leak into a durable reference.
     */
    public StableScope stableScope() {
        return new StableScope(projectCode, environment, externalServiceKey);
    }

    public record StableScope(String projectCode, String environment,
                              @JsonInclude(JsonInclude.Include.NON_NULL) String externalServiceKey) {
        public StableScope(String projectCode, String environment) { this(projectCode, environment, null); }
    }

    private static String normalizeProjectCode(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("HTTP API projectCode is required");
        }
        String normalized = raw.trim().toLowerCase(Locale.ROOT);
        if (normalized.length() > 96 || !normalized.matches("[a-z0-9_-]+")) {
            throw new IllegalArgumentException("HTTP API projectCode is invalid");
        }
        return normalized;
    }

    private static String normalizeEnvironment(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("HTTP API environment is required");
        }
        String normalized = raw.trim().toLowerCase(Locale.ROOT);
        if (normalized.length() > 32 || !normalized.matches("[a-z0-9_.-]+")) {
            throw new IllegalArgumentException("HTTP API environment is invalid");
        }
        return normalized;
    }
}
