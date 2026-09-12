package com.enterprise.ai.runtime.debug;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.Objects;

/** Platform session ownership is separate from the Workflow's execution identity and credentials. */
public record RuntimeDebugSessionOwner(String tenantId, String userId) {
    public RuntimeDebugSessionOwner {
        if (tenantId == null || tenantId.isBlank() || tenantId.trim().length() > 96
                || userId == null || userId.isBlank() || userId.trim().length() > 128)
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Platform session identity is required");
        tenantId = tenantId.trim();
        userId = userId.trim();
    }

    void requireMatches(RuntimeExecutableDebugSessionEntity session) {
        if (session == null || !Objects.equals(tenantId, session.getOwnerTenantId())
                || !Objects.equals(userId, session.getOwnerUserId()))
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Debug session is unavailable");
    }
}
