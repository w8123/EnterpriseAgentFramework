package com.enterprise.ai.control.identity;

import java.time.LocalDateTime;
import java.util.List;

/** Server-attested platform principal used by console authorization. */
public record PlatformAuthenticatedSession(
        PlatformUserEntity user,
        String sessionId,
        LocalDateTime expiresAt,
        List<String> roles,
        List<String> permissions,
        List<PlatformPermissionGrant> permissionGrants
) {

    public boolean hasGlobalPermission(String requiredPermission) {
        return permissionGrants.stream().anyMatch(grant -> grant.grantsGlobal(requiredPermission));
    }
}
