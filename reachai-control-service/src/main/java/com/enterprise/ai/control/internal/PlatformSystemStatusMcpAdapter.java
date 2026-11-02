package com.enterprise.ai.control.internal;

import com.enterprise.ai.control.identity.PlatformAuthenticatedSession;
import com.enterprise.ai.control.identity.PlatformConsolePermissionPolicy;
import com.enterprise.ai.control.identity.PlatformDelegatedRequestScope;
import com.enterprise.ai.control.identity.PlatformIdentityController;
import com.enterprise.ai.control.mcp.application.port.McpPlatformSystemStatusPort;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/** The health and system check controllers rely on the console route rule, so this adapter applies the same rule first. */
@Component
@RequiredArgsConstructor
class PlatformSystemStatusMcpAdapter implements McpPlatformSystemStatusPort {
    private final InternalServicesHealthController health;
    private final PlatformSystemCheckController systemCheck;
    private final PlatformIdentityController identity;
    private final PlatformDelegatedRequestScope scope;

    @Override
    public ResponseEntity<?> read(PlatformAuthenticatedSession actor, Read read) {
        String path = switch (read) {
            case SERVICES_HEALTH -> "/api/internal-services/health";
            case SYSTEM_CHECK -> "/api/platform/system-check";
            case AUTH_PROVIDERS -> "/api/platform/auth-providers";
        };
        var rule = PlatformConsolePermissionPolicy.ruleFor(path).orElseThrow();
        if (actor == null || actor.user() == null || !PlatformConsolePermissionPolicy.admits(rule, "GET", path, actor)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "platform permission is required");
        }
        return scope.run(actor, request -> switch (read) {
            case SERVICES_HEALTH -> health.health();
            case SYSTEM_CHECK -> ResponseEntity.ok(systemCheck.systemCheck());
            case AUTH_PROVIDERS -> identity.listAuthProviders(request);
        });
    }
}
