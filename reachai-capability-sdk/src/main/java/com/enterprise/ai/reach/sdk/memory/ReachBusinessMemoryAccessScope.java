package com.enterprise.ai.reach.sdk.memory;

/**
 * Proof that a business-memory source row was re-authorized for the current
 * signed ReachAI tenant and user.
 *
 * <p>The business host must pass the tenant read from the authoritative row
 * (or the explicitly configured deployment tenant for a single-tenant
 * system), not a value copied from the Knowledge hit.</p>
 */
public final class ReachBusinessMemoryAccessScope {

    private final String tenantId;
    private final String currentUserId;
    private final String projectCode;
    private final String resourceType;
    private final String resourceId;

    private ReachBusinessMemoryAccessScope(String tenantId,
                                           String currentUserId,
                                           String projectCode,
                                           String resourceType,
                                           String resourceId) {
        this.tenantId = tenantId;
        this.currentUserId = currentUserId;
        this.projectCode = projectCode;
        this.resourceType = resourceType;
        this.resourceId = resourceId;
    }

    public static ReachBusinessMemoryAccessScope authorizeRow(
            String invocationTenantId,
            String authoritativeRowTenantId,
            String currentUserId,
            String projectCode,
            String resourceType,
            String requestedResourceId,
            String authoritativeResourceId,
            boolean visibleToCurrentUser) {
        String invocationTenant = requiredIdentifier(invocationTenantId, "invocationTenantId", 96);
        String rowTenant = requiredIdentifier(authoritativeRowTenantId, "authoritativeRowTenantId", 96);
        if (!invocationTenant.equals(rowTenant)) {
            throw new SecurityException("business-memory tenant scope denied");
        }
        String user = required(currentUserId, "currentUserId", 128);
        String project = requiredIdentifier(projectCode, "projectCode", 96);
        String type = requiredIdentifier(resourceType, "resourceType", 96);
        String requestedId = required(requestedResourceId, "requestedResourceId", 256);
        String actualId = required(authoritativeResourceId, "authoritativeResourceId", 256);
        if (!requestedId.equals(actualId)) {
            throw new SecurityException("business-memory source returned a different resource");
        }
        if (!visibleToCurrentUser) {
            throw new SecurityException("business-memory row scope denied");
        }
        return new ReachBusinessMemoryAccessScope(invocationTenant, user, project, type, actualId);
    }

    public String getTenantId() {
        return tenantId;
    }

    public String getCurrentUserId() {
        return currentUserId;
    }

    public String getProjectCode() {
        return projectCode;
    }

    public String getResourceType() {
        return resourceType;
    }

    public String getResourceId() {
        return resourceId;
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
        if (normalized.length() == 0) {
            throw new IllegalArgumentException(field + " is required");
        }
        if (normalized.length() > max) {
            throw new IllegalArgumentException(field + " exceeds " + max + " characters");
        }
        return normalized;
    }
}
