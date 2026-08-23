package com.enterprise.ai.control.identity;

import org.springframework.util.StringUtils;

/** A permission resolved from a role binding, including its resource scope. */
public record PlatformPermissionGrant(
        String permissionCode,
        String scopeType,
        String scopeValue
) {

    public boolean grantsGlobal(String requiredPermission) {
        return "GLOBAL".equalsIgnoreCase(scopeType)
                && "*".equals(scopeValue)
                && grantsPermission(requiredPermission);
    }

    /**
     * Applies the role-binding hierarchy used by Knowledge resources: GLOBAL
     * can reach every resource, WORKSPACE can reach its projects, while a
     * PROJECT grant is limited to the matching project. SHARED remains global.
     */
    public boolean grantsResource(String requiredPermission,
                                  String resourceScope,
                                  String workspaceId,
                                  String projectCode) {
        if (!grantsPermission(requiredPermission)) {
            return false;
        }
        if (grantsGlobal(requiredPermission)) {
            return true;
        }
        String normalizedScope = resourceScope == null ? "" : resourceScope.trim().toUpperCase(java.util.Locale.ROOT);
        if ("WORKSPACE".equals(normalizedScope)) {
            return "WORKSPACE".equalsIgnoreCase(scopeType) && same(scopeValue, workspaceId);
        }
        if ("PROJECT".equals(normalizedScope)) {
            return ("PROJECT".equalsIgnoreCase(scopeType) && same(scopeValue, projectCode))
                    || ("WORKSPACE".equalsIgnoreCase(scopeType) && same(scopeValue, workspaceId));
        }
        return false;
    }

    public boolean hasUsableScope() {
        return StringUtils.hasText(scopeType) && StringUtils.hasText(scopeValue);
    }

    private boolean grantsPermission(String requiredPermission) {
        return "*".equals(permissionCode) || requiredPermission.equals(permissionCode);
    }

    private static boolean same(String left, String right) {
        return StringUtils.hasText(left) && StringUtils.hasText(right) && left.trim().equals(right.trim());
    }
}
