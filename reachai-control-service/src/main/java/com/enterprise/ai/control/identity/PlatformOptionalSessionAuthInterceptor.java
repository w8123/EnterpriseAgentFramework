package com.enterprise.ai.control.identity;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Public Agent execution may omit platform credentials. Presented credentials
 * must satisfy the same session and Cookie CSRF checks as the console before
 * they can supply a trusted execution identity.
 */
@Component
@RequiredArgsConstructor
public class PlatformOptionalSessionAuthInterceptor implements HandlerInterceptor {
    private final PlatformConsoleAuthInterceptor sessionAuth;
    private final PlatformSessionCookieService sessionCookies;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!StringUtils.hasText(request.getHeader(HttpHeaders.AUTHORIZATION))
                && sessionCookies.readSessionToken(request).isEmpty()) {
            return true;
        }
        return sessionAuth.preHandle(request, response, handler);
    }
}
