package com.enterprise.ai.control.identity;

import com.enterprise.ai.control.internalauth.InternalServiceAuthRequestAttributes;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.Cookie;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.server.ResponseStatusException;

import java.util.Collections;
import java.util.Enumeration;
import java.util.Map;
import java.util.function.Function;

/** Temporarily presents an attested AI delegate to existing console authorization. */
@Component
public final class PlatformDelegatedRequestScope {
    public ResponseEntity<?> run(PlatformAuthenticatedSession actor,
                                 Function<HttpServletRequest, ResponseEntity<?>> action) {
        if (actor == null || actor.user() == null || actor.user().getId() == null
                || !(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "platform request is required");
        }
        HttpServletRequest request = attributes.getRequest();
        String sessionKey = PlatformConsoleAuthInterceptor.SESSION_REQUEST_ATTRIBUTE;
        String userKey = PlatformConsoleAuthInterceptor.USER_REQUEST_ATTRIBUTE;
        String actorKey = InternalServiceAuthRequestAttributes.PLATFORM_SESSION_ACTOR_ID;
        Object oldSession = request.getAttribute(sessionKey);
        Object oldUser = request.getAttribute(userKey);
        Object oldActor = request.getAttribute(actorKey);
        try {
            request.setAttribute(sessionKey, actor);
            request.setAttribute(userKey, actor.user());
            request.setAttribute(actorKey, String.valueOf(actor.user().getId()));
            return action.apply(new ConsoleRequest(request));
        } finally {
            restore(request, sessionKey, oldSession);
            restore(request, userKey, oldUser);
            restore(request, actorKey, oldActor);
        }
    }

    private static void restore(HttpServletRequest request, String key, Object previous) {
        if (previous == null) request.removeAttribute(key);
        else request.setAttribute(key, previous);
    }

    /** MCP URL parameters and credentials are not console action parameters or credentials. */
    private static final class ConsoleRequest extends HttpServletRequestWrapper {
        private ConsoleRequest(HttpServletRequest request) { super(request); }
        @Override public String getParameter(String name) { return null; }
        @Override public Map<String, String[]> getParameterMap() { return Map.of(); }
        @Override public Enumeration<String> getParameterNames() { return Collections.emptyEnumeration(); }
        @Override public String[] getParameterValues(String name) { return null; }
        @Override public String getQueryString() { return null; }
        @Override public Cookie[] getCookies() { return null; }
        @Override public String getHeader(String name) {
            return isCredentialHeader(name) ? null : super.getHeader(name);
        }
        @Override public Enumeration<String> getHeaders(String name) {
            return isCredentialHeader(name) ? Collections.emptyEnumeration() : super.getHeaders(name);
        }
        private boolean isCredentialHeader(String name) {
            return HttpHeaders.AUTHORIZATION.equalsIgnoreCase(name) || HttpHeaders.COOKIE.equalsIgnoreCase(name)
                    || PlatformConsoleAuthInterceptor.CSRF_HEADER.equalsIgnoreCase(name);
        }
    }
}
