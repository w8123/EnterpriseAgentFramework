package com.enterprise.ai.control.identity;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Owns the browser-only transport for a platform login session. */
@Component
@RequiredArgsConstructor
public class PlatformSessionCookieService {

    public static final String SESSION_COOKIE_NAME = "reachai_platform_session";
    /** The console reaches Knowledge (/ai) and Model (/model) through Control, so the session covers the site. */
    public static final String SESSION_COOKIE_PATH = "/";
    /** Earlier releases scoped the cookie to /api; it is expired on login and logout so it cannot shadow the current one. */
    public static final String LEGACY_SESSION_COOKIE_PATH = "/api";

    private final PlatformAuthProperties authProperties;

    public Optional<String> readSessionToken(HttpServletRequest request) {
        return readSessionTokens(request).stream().findFirst();
    }

    /**
     * Every session cookie the browser sent, most specific path first. A stale
     * /api cookie from an earlier release can precede the current one.
     */
    public List<String> readSessionTokens(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return List.of();
        }
        List<String> tokens = new ArrayList<>();
        for (Cookie cookie : cookies) {
            if (SESSION_COOKIE_NAME.equals(cookie.getName()) && StringUtils.hasText(cookie.getValue())) {
                tokens.add(cookie.getValue());
            }
        }
        return List.copyOf(tokens);
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

    public String expireLegacyCookie(HttpServletRequest request) {
        return cookieBuilder(request, "", LEGACY_SESSION_COOKIE_PATH)
                .maxAge(Duration.ZERO)
                .build()
                .toString();
    }

    private ResponseCookie.ResponseCookieBuilder cookieBuilder(HttpServletRequest request, String value) {
        return cookieBuilder(request, value, SESSION_COOKIE_PATH);
    }

    private ResponseCookie.ResponseCookieBuilder cookieBuilder(HttpServletRequest request, String value, String path) {
        return ResponseCookie.from(SESSION_COOKIE_NAME, value)
                .httpOnly(true)
                .secure(secureCookie(request))
                .sameSite("Strict")
                .path(path);
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
