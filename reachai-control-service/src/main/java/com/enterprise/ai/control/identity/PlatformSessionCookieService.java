package com.enterprise.ai.control.identity;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.util.Optional;

/** Owns the browser-only transport for a platform login session. */
@Component
@RequiredArgsConstructor
public class PlatformSessionCookieService {

    public static final String SESSION_COOKIE_NAME = "reachai_platform_session";
    public static final String SESSION_COOKIE_PATH = "/api";

    private final PlatformAuthProperties authProperties;

    public Optional<String> readSessionToken(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return Optional.empty();
        }
        for (Cookie cookie : cookies) {
            if (SESSION_COOKIE_NAME.equals(cookie.getName()) && StringUtils.hasText(cookie.getValue())) {
                return Optional.of(cookie.getValue());
            }
        }
        return Optional.empty();
    }

    public String issueCookie(HttpServletRequest request, String rawSessionToken) {
        return cookieBuilder(request, rawSessionToken)
                .maxAge(authProperties.getSessionTtl())
                .build()
                .toString();
    }

    public String expireCookie(HttpServletRequest request) {
        return cookieBuilder(request, "")
                .maxAge(Duration.ZERO)
                .build()
                .toString();
    }

    private ResponseCookie.ResponseCookieBuilder cookieBuilder(HttpServletRequest request, String value) {
        return ResponseCookie.from(SESSION_COOKIE_NAME, value)
                .httpOnly(true)
                .secure(secureCookie(request))
                .sameSite("Strict")
                .path(SESSION_COOKIE_PATH);
    }

    private boolean secureCookie(HttpServletRequest request) {
        PlatformAuthProperties.SecureMode secureMode = authProperties.getSessionCookie().getSecure();
        if (secureMode == PlatformAuthProperties.SecureMode.ALWAYS) {
            return true;
        }
        if (secureMode == PlatformAuthProperties.SecureMode.NEVER) {
            return false;
        }
        if (request.isSecure()) {
            return true;
        }
        String forwardedProto = request.getHeader("X-Forwarded-Proto");
        if (!StringUtils.hasText(forwardedProto)) {
            return false;
        }
        String firstProto = forwardedProto.split(",", 2)[0].trim();
        return "https".equalsIgnoreCase(firstProto);
    }
}
