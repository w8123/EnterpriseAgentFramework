package com.enterprise.ai.reach.sdk.memory;

import com.enterprise.ai.reach.sdk.annotation.ReachOutput;
import com.fasterxml.jackson.annotation.JsonIgnore;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * JDK8-compatible response DTO for a resolver Capability. The business host
 * must authorize the signed ReachAI invocation before returning this object.
 */
public class ReachBusinessMemoryResolution {

    public static final String SCHEMA = "reachai-business-memory-resolution-v1";

    @ReachOutput(description = "Fixed ReachAI business-memory resolution schema")
    private String schema = SCHEMA;

    @ReachOutput(description = "Tenant authorized by the current signed invocation")
    private String tenantId;

    @ReachOutput(description = "ReachAI project that owns the resolver Capability")
    private String projectCode;

    @ReachOutput(description = "Business system that owns the authoritative record")
    private String sourceSystem;

    @ReachOutput(description = "Resolved business resource type")
    private String resourceType;

    @ReachOutput(description = "Resolved business resource id", sensitive = true)
    private String resourceId;

    @ReachOutput(description = "Current source-system version, which may differ from the index hint")
    private String sourceVersion;

    @ReachOutput(description = "Current business data already filtered for the signed user", sensitive = true)
    private Map<String, Object> data = new LinkedHashMap<String, Object>();

    @ReachOutput(description = "UTC date-time at which the business source was resolved")
    private String resolvedAt;

    private boolean rowAuthorizationVerified;

    public ReachBusinessMemoryResolution() {
    }

    /**
     * Prefer {@link #createAuthorized(ReachBusinessMemoryAccessScope, String, String, Map)}.
     * Results created through this compatibility factory are rejected by the
     * Starter when returned from a {@code business-memory-resolver} Capability.
     */
    @Deprecated
    public static ReachBusinessMemoryResolution create(String tenantId,
                                                       String projectCode,
                                                       String sourceSystem,
                                                       String resourceType,
                                                       String resourceId,
                                                       String sourceVersion,
                                                       Map<String, Object> data) {
        ReachBusinessMemoryResolution result = new ReachBusinessMemoryResolution();
        result.setTenantId(requiredIdentifier(tenantId, "tenantId", 96));
        result.setProjectCode(requiredIdentifier(projectCode, "projectCode", 96));
        result.setSourceSystem(requiredIdentifier(sourceSystem, "sourceSystem", 96));
        result.setResourceType(requiredIdentifier(resourceType, "resourceType", 96));
        result.setResourceId(required(resourceId, "resourceId", 256));
        result.setSourceVersion(required(sourceVersion, "sourceVersion", 128));
        if (data == null) throw new IllegalArgumentException("data is required");
        result.setData(data);
        result.setResolvedAt(OffsetDateTime.now(ZoneOffset.UTC).toString());
        return result;
    }

    public static ReachBusinessMemoryResolution createAuthorized(
            ReachBusinessMemoryAccessScope accessScope,
            String sourceSystem,
            String sourceVersion,
            Map<String, Object> data) {
        if (accessScope == null) {
            throw new IllegalArgumentException("accessScope is required");
        }
        ReachBusinessMemoryResolution result = create(
                accessScope.getTenantId(),
                accessScope.getProjectCode(),
                sourceSystem,
                accessScope.getResourceType(),
                accessScope.getResourceId(),
                sourceVersion,
                data);
        result.rowAuthorizationVerified = true;
        return result;
    }

    public String getSchema() {
        return schema;
    }

    public void setSchema(String schema) {
        if (!SCHEMA.equals(schema)) {
            throw new IllegalArgumentException("unsupported business memory resolution schema");
        }
        this.schema = schema;
    }

    public String getTenantId() {
        return tenantId;
    }

    public void setTenantId(String tenantId) {
        this.tenantId = tenantId;
    }

    public String getProjectCode() {
        return projectCode;
    }

    public void setProjectCode(String projectCode) {
        this.projectCode = projectCode;
    }

    public String getSourceSystem() {
        return sourceSystem;
    }

    public void setSourceSystem(String sourceSystem) {
        this.sourceSystem = sourceSystem;
    }

    public String getResourceType() {
        return resourceType;
    }

    public void setResourceType(String resourceType) {
        this.resourceType = resourceType;
    }

    public String getResourceId() {
        return resourceId;
    }

    public void setResourceId(String resourceId) {
        this.resourceId = resourceId;
    }

    public String getSourceVersion() {
        return sourceVersion;
    }

    public void setSourceVersion(String sourceVersion) {
        this.sourceVersion = sourceVersion;
    }

    public Map<String, Object> getData() {
        return data;
    }

    public void setData(Map<String, Object> data) {
        this.data = data == null
                ? new LinkedHashMap<String, Object>()
                : new LinkedHashMap<String, Object>(data);
    }

    public String getResolvedAt() {
        return resolvedAt;
    }

    public void setResolvedAt(String resolvedAt) {
        this.resolvedAt = resolvedAt;
    }

    @JsonIgnore
    public boolean isRowAuthorizationVerified() {
        return rowAuthorizationVerified;
    }

    private static String requiredIdentifier(String value, String field, int max) {
        String normalized = required(value, field, max);
        if (!normalized.matches("[A-Za-z0-9._:-]+")) {
            throw new IllegalArgumentException(field + " contains unsupported characters");
        }
        return normalized;
    }

    private static String required(String value, String field, int max) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.length() == 0) throw new IllegalArgumentException(field + " is required");
        if (normalized.length() > max) throw new IllegalArgumentException(field + " exceeds " + max + " characters");
        return normalized;
    }
}
