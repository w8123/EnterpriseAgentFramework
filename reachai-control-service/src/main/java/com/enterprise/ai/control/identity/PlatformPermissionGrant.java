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
                && ("*".equals(permissionCode) || requiredPermission.equals(permissionCode));
    }

    public boolean hasUsableScope() {
        return StringUtils.hasText(scopeType) && StringUtils.hasText(scopeValue);
    }
}
