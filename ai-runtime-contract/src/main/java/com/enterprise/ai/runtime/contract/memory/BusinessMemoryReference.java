package com.enterprise.ai.runtime.contract.memory;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A search projection pointer to business-owned data. It is never authoritative
 * by itself: Runtime must call resolverCapabilityKey under the current trusted
 * business identity before using the record as a current business fact.
 */
public record BusinessMemoryReference(
        String schema,
        String tenantId,
        String projectCode,
        String sourceSystem,
        String resourceType,
        String resourceId,
        String sourceVersion,
        String resolverCapabilityKey,
        OffsetDateTime observedAt) {

    public static final String SCHEMA = "reachai-business-memory-reference-v1";

    public BusinessMemoryReference {
        schema = required(schema, "schema", 64);
        if (!SCHEMA.equals(schema)) {
            throw new IllegalArgumentException("unsupported business memory reference schema");
        }
        tenantId = identifier(tenantId, "tenantId", 96);
        projectCode = identifier(projectCode, "projectCode", 96);
        sourceSystem = identifier(sourceSystem, "sourceSystem", 96);
        resourceType = identifier(resourceType, "resourceType", 96);
        resourceId = required(resourceId, "resourceId", 256);
        sourceVersion = required(sourceVersion, "sourceVersion", 128);
        resolverCapabilityKey = identifier(resolverCapabilityKey, "resolverCapabilityKey", 128);
        if (observedAt == null) throw new IllegalArgumentException("observedAt is required");
    }

    public static BusinessMemoryReference create(
            String tenantId,
            String projectCode,
            String sourceSystem,
            String resourceType,
            String resourceId,
            String sourceVersion,
            String resolverCapabilityKey,
            OffsetDateTime observedAt) {
        return new BusinessMemoryReference(SCHEMA, tenantId, projectCode, sourceSystem,
                resourceType, resourceId, sourceVersion, resolverCapabilityKey, observedAt);
    }

    /** Arguments passed to the resolver Capability; caller identity is never copied from the index. */
    public Map<String, Object> resolverArguments() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("resourceType", resourceType);
        result.put("resourceId", resourceId);
        result.put("expectedSourceVersion", sourceVersion);
        return Map.copyOf(result);
    }

    public boolean authoritative() {
        return false;
    }

    public boolean hydrationRequired() {
        return true;
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
