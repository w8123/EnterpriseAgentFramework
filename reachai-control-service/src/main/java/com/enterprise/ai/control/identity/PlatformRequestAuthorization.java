package com.enterprise.ai.control.identity;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Request-scoped entrypoint for Control console authorization.
 *
 * <p>The authentication interceptor attests the platform session once. Domain
 * controllers use this service to enforce permissions and scopes without
 * duplicating request-attribute parsing or trusting actor fields from request
 * bodies.</p>
 */
@Service
@RequiredArgsConstructor
public class PlatformRequestAuthorization {

    private final PlatformAuthorizationService authorizationService;

    public PlatformAuthenticatedSession requireAuthenticated(HttpServletRequest request) {
        Object candidate = request == null
                ? null
                : request.getAttribute(PlatformConsoleAuthInterceptor.SESSION_REQUEST_ATTRIBUTE);
        if (candidate instanceof PlatformAuthenticatedSession session) {
            return session;
        }
        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "platform session is required");
    }

    public PlatformAuthenticatedSession requireGlobalPermission(
            HttpServletRequest request,
            String permission) {
        PlatformAuthenticatedSession session = requireAuthenticated(request);
        authorizationService.requireGlobalPermission(session, permission);
        return session;
    }

    public PlatformAuthenticatedSession requirePermission(
            HttpServletRequest request,
            String permission) {
        PlatformAuthenticatedSession session = requireAuthenticated(request);
        authorizationService.requirePermission(session, permission);
        return session;
    }

    public PlatformAuthenticatedSession requireResourcePermission(
            HttpServletRequest request,
            String permission,
            String resourceScope,
            String workspaceId,
            String projectCode) {
        PlatformAuthenticatedSession session = requireAuthenticated(request);
        authorizationService.requireResourcePermission(
                session, permission, resourceScope, workspaceId, projectCode);
        return session;
    }
}
