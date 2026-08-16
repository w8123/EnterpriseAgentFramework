package com.enterprise.ai.runtime.contract.memory;

import java.time.OffsetDateTime;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Current business data returned by a resolver Capability after it has
 * re-authorized the active Runtime principal. Unlike a search projection,
 * this envelope is authoritative for the point-in-time resolution only.
 */
public record BusinessMemoryResolution(
        String schema,
        String tenantId,
        String projectCode,
        String sourceSystem,
        String resourceType,
        String resourceId,
        String sourceVersion,
        Map<String, Object> data,
        OffsetDateTime resolvedAt) {

    public static final String SCHEMA = "reachai-business-memory-resolution-v1";

    public BusinessMemoryResolution {
        schema = required(schema, "schema", 64);
        if (!SCHEMA.equals(schema)) {
            throw new IllegalArgumentException("unsupported business memory resolution schema");
        }
        tenantId = identifier(tenantId, "tenantId", 96);
        projectCode = identifier(projectCode, "projectCode", 96);
        sourceSystem = identifier(sourceSystem, "sourceSystem", 96);
        resourceType = identifier(resourceType, "resourceType", 96);
        resourceId = required(resourceId, "resourceId", 256);
        sourceVersion = required(sourceVersion, "sourceVersion", 128);
        if (data == null) throw new IllegalArgumentException("data is required");
        data = Collections.unmodifiableMap(new LinkedHashMap<>(data));
        if (resolvedAt == null) throw new IllegalArgumentException("resolvedAt is required");
    }

    public static BusinessMemoryResolution create(
            String tenantId,
            String projectCode,
            String sourceSystem,
            String resourceType,
            String resourceId,
            String sourceVersion,
            Map<String, Object> data,
            OffsetDateTime resolvedAt) {
        return new BusinessMemoryResolution(SCHEMA, tenantId, projectCode, sourceSystem,
                resourceType, resourceId, sourceVersion, data, resolvedAt);
    }

    public boolean authoritative() {
        return true;
    }

    public boolean hydrationRequired() {
        return false;
    }

    private static String identifier(String value, String field, int max) {
        String normalized = required(value, field, max);
        if (!normalized.matches("[A-Za-z0-9._:-]+")) {
            throw new IllegalArgumentException(field + " contains unsupported characters");
        }
        return normalized;
    }

    private static String required(String value, String field, int max) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException(field + " is required");
        if (normalized.length() > max) throw new IllegalArgumentException(field + " exceeds " + max + " characters");
        return normalized;
    }
}
