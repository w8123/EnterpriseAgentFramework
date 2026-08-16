package com.enterprise.ai.control.identity;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.nio.charset.StandardCharsets;

/**
 * Requires a live ReachAI platform login for mutating and reading the new
 * platform-console workbench APIs.
 */
@Component
@RequiredArgsConstructor
public class PlatformConsoleAuthInterceptor implements HandlerInterceptor {

    public static final String USER_REQUEST_ATTRIBUTE =
            "reachai.platform.authenticated-user";
    public static final String SESSION_REQUEST_ATTRIBUTE =
            "reachai.platform.authenticated-session";
    public static final String AUTH_FAILURE_HEADER = "X-ReachAI-Auth-Failure";
    public static final String PLATFORM_SESSION_INVALID = "PLATFORM_SESSION_INVALID";

    private final PlatformBearerAuthService bearerAuthService;

    @Override
    public boolean preHandle(
            HttpServletRequest request,
            HttpServletResponse response,
            Object handler) {
        if (HttpMethod.OPTIONS.matches(request.getMethod())) {
            return true;
        }
        PlatformAuthenticatedSession session = bearerAuthService.resolveBearerSession(
                        request.getHeader(HttpHeaders.AUTHORIZATION))
                .orElse(null);
        if (session == null) {
            writeSessionFailure(response);
            return false;
        }
        // Keep the old user attribute until all existing consumers migrate to
        // the session principal. Authorization decisions use the new session.
        request.setAttribute(USER_REQUEST_ATTRIBUTE, session.user());
        request.setAttribute(SESSION_REQUEST_ATTRIBUTE, session);
        return true;
    }

    private void writeSessionFailure(HttpServletResponse response) {
        response.setHeader(AUTH_FAILURE_HEADER, PLATFORM_SESSION_INVALID);
        response.setHeader("Cache-Control", "no-store");
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        try {
            response.getWriter().write("{\"code\":\"" + PLATFORM_SESSION_INVALID
                    + "\",\"credentialDomain\":\""
                    + PlatformConsoleRoutePolicy.CredentialDomain.PLATFORM_SESSION.name()
                    + "\",\"nextAction\":\"LOGIN_REQUIRED\"}");
        } catch (java.io.IOException ignored) {
            // A closed client connection must not turn a rejected request into an allowed one.
        }
    }
}
