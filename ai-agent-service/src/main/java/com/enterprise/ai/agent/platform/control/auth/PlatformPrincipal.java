package com.enterprise.ai.agent.platform.control.auth;

import java.util.Set;

public record PlatformPrincipal(
        Long userId,
        String username,
        String displayName,
        Set<String> roles,
        Set<String> permissions,
        Set<RoleGrant> roleGrants
) {
    public PlatformPrincipal(Long userId,
                             String username,
                             String displayName,
                             Set<String> roles,
                             Set<String> permissions) {
        this(userId, username, displayName, roles, permissions, Set.of());
    }

    public PlatformPrincipal {
        roles = roles == null ? Set.of() : roles;
        permissions = permissions == null ? Set.of() : permissions;
        roleGrants = roleGrants == null ? Set.of() : roleGrants;
    }

    public record RoleGrant(String roleCode, String scopeType, String scopeValue) {
    }
}
