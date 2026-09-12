package com.enterprise.ai.control.identity;

import java.time.LocalDateTime;
import java.util.List;

/** Server-attested platform principal used by console authorization. */
public record PlatformAuthenticatedSession(
        PlatformPrincipal user,
        String sessionId,
        LocalDateTime expiresAt,
        List<String> roles,
        List<String> permissions,
        List<PlatformPermissionGrant> permissionGrants
) {

    public PlatformAuthenticatedSession {
        roles = roles == null ? List.of() : List.copyOf(roles);
        permissions = permissions == null ? List.of() : List.copyOf(permissions);
        permissionGrants = permissionGrants == null ? List.of() : List.copyOf(permissionGrants);
    }

    public boolean hasGlobalPermission(String requiredPermission) {
        return permissionGrants.stream().anyMatch(grant -> grant.grantsGlobal(requiredPermission));
    }

    public boolean hasResourcePermission(String requiredPermission,
                                         String resourceScope,
                                         String workspaceId,
                                         String projectCode) {
        return permissionGrants.stream().anyMatch(grant -> grant.grantsResource(
                requiredPermission, resourceScope, workspaceId, projectCode));
    }
}
