package com.enterprise.ai.control.identity;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Requires a live ReachAI platform login for mutating and reading the new
 * platform-console workbench APIs.
 */
@Component
@RequiredArgsConstructor
public class PlatformConsoleAuthInterceptor implements HandlerInterceptor {

    public static final String USER_REQUEST_ATTRIBUTE =
            "reachai.platform.authenticated-user";

    private final PlatformBearerAuthService bearerAuthService;

    @Override
    public boolean preHandle(
            HttpServletRequest request,
            HttpServletResponse response,
            Object handler) {
        if (HttpMethod.OPTIONS.matches(request.getMethod())) {
            return true;
        }
        PlatformUserEntity user = bearerAuthService.resolveBearerUser(
                        request.getHeader(HttpHeaders.AUTHORIZATION))
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.UNAUTHORIZED,
                        "live ReachAI platform login is required"));
        request.setAttribute(USER_REQUEST_ATTRIBUTE, user);
        return true;
    }
}
