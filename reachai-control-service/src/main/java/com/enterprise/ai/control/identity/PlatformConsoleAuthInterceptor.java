package com.enterprise.ai.control.identity;

import com.enterprise.ai.control.internalauth.InternalServiceAuthRequestAttributes;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.HandlerInterceptor;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

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
    public static final String CSRF_HEADER = "X-ReachAI-CSRF";
    public static final String PLATFORM_CSRF_INVALID = "PLATFORM_CSRF_INVALID";
    public static final String PLATFORM_PERMISSION_DENIED = "PLATFORM_PERMISSION_DENIED";

    private final PlatformBearerAuthService bearerAuthService;
    private final PlatformSessionCookieService sessionCookieService;

    @Override
    public boolean preHandle(
            HttpServletRequest request,
            HttpServletResponse response,
            Object handler) {
        if (HttpMethod.OPTIONS.matches(request.getMethod())) {
            return true;
        }
        String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
        boolean bearerPresented = StringUtils.hasText(authorization);
        List<String> cookieTokens = bearerPresented
                ? List.of()
                : sessionCookieService.readSessionTokens(request);
        // A stale cookie from an older path can precede the live one, so try each.
        PlatformAuthenticatedSession session = (bearerPresented
                ? bearerAuthService.resolveBearerSession(authorization)
                : cookieTokens.stream()
                        .map(bearerAuthService::resolveSessionToken)
                        .flatMap(Optional::stream)
                        .findFirst())
                .orElse(null);
        if (!cookieTokens.isEmpty() && (session == null || cookieTokens.size() > 1)) {
            response.addHeader(HttpHeaders.SET_COOKIE, sessionCookieService.expireLegacyCookie(request));
        }
        if (session == null) {
            if (!cookieTokens.isEmpty()) {
                response.addHeader(HttpHeaders.SET_COOKIE, sessionCookieService.expireCookie(request));
            }
            writeSessionFailure(response);
            return false;
        }
        if (!bearerPresented && unsafeMethod(request.getMethod())
                && !csrfMatches(request.getHeader(CSRF_HEADER), session.sessionId())) {
            writeCsrfFailure(response);
            return false;
        }
        String path = consolePath(request);
        Optional<PlatformConsolePermissionPolicy.Rule> rule = PlatformConsolePermissionPolicy.ruleFor(path);
        if (rule.isEmpty() || !PlatformConsolePermissionPolicy.admits(
                rule.get(), request.getMethod(), path, session)) {
            writePermissionFailure(response, rule.map(value -> value.requiredAnyOf(request.getMethod())).orElse(null));
            return false;
        }
        // Keep the old user attribute until all existing consumers migrate to
        // the session principal. Authorization decisions use the new session.
        request.setAttribute(USER_REQUEST_ATTRIBUTE, session.user());
        request.setAttribute(SESSION_REQUEST_ATTRIBUTE, session);
        if (session.user() != null && session.user().getId() != null) {
            request.setAttribute(
                    InternalServiceAuthRequestAttributes.PLATFORM_SESSION_ACTOR_ID,
                    String.valueOf(session.user().getId()));
        }
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

    private void writeCsrfFailure(HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        response.setStatus(HttpStatus.FORBIDDEN.value());
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        try {
            response.getWriter().write("{\"code\":\"" + PLATFORM_CSRF_INVALID
                    + "\",\"credentialDomain\":\""
                    + PlatformConsoleRoutePolicy.CredentialDomain.PLATFORM_SESSION.name()
                    + "\",\"nextAction\":\"REFRESH_SESSION\"}");
        } catch (java.io.IOException ignored) {
            // A closed client connection must not turn a rejected request into an allowed one.
        }
    }

    private void writePermissionFailure(HttpServletResponse response, List<String> required) {
        response.setHeader("Cache-Control", "no-store");
        response.setStatus(HttpStatus.FORBIDDEN.value());
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        String requiredJson = required == null ? "[]" : required.stream()
                .map(permission -> "\"" + permission + "\"")
                .collect(java.util.stream.Collectors.joining(",", "[", "]"));
        try {
            response.getWriter().write("{\"code\":\"" + PLATFORM_PERMISSION_DENIED
                    + "\",\"message\":\"当前账号没有访问该功能的权限\",\"requiredAnyOf\":" + requiredJson + "}");
        } catch (java.io.IOException ignored) {
            // A closed client connection must not turn a rejected request into an allowed one.
        }
    }

    private static String consolePath(HttpServletRequest request) {
        String uri = request.getRequestURI();
        String contextPath = request.getContextPath();
        if (uri != null && StringUtils.hasText(contextPath) && uri.startsWith(contextPath)) {
            return uri.substring(contextPath.length());
        }
        return uri;
    }

    private boolean unsafeMethod(String method) {
        String normalized = method == null ? "" : method.toUpperCase(Locale.ROOT);
        return !HttpMethod.GET.matches(normalized)
                && !HttpMethod.HEAD.matches(normalized)
                && !HttpMethod.OPTIONS.matches(normalized)
                && !HttpMethod.TRACE.matches(normalized);
    }

    private boolean csrfMatches(String supplied, String expected) {
        if (!StringUtils.hasText(supplied) || !StringUtils.hasText(expected)) {
            return false;
        }
        return MessageDigest.isEqual(
                supplied.getBytes(StandardCharsets.UTF_8),
                expected.getBytes(StandardCharsets.UTF_8));
    }
}
