package com.enterprise.ai.control.a2a.api.management;

import com.enterprise.ai.control.identity.PlatformAuthenticatedSession;
import com.enterprise.ai.control.identity.PlatformConsoleAuthInterceptor;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

@Component
public class A2aHubManagementAccess {

    public static final String READ = "a2a-hub:read";
    public static final String MANAGE_PUBLICATIONS = "a2a-hub:publication:manage";
    public static final String MANAGE_REMOTE_AGENTS = "a2a-hub:remote-agent:manage";
    public static final String MANAGE_TRUST = "a2a-hub:trust:manage";
    public static final String MANAGE_CREDENTIALS = "a2a-hub:credential:manage";
    public static final String OPERATE_TASKS = "a2a-hub:task:operate";
    public static final String READ_PAYLOAD = "a2a-hub:payload:read";
    public static final String RUN_CONFORMANCE = "a2a-hub:conformance:run";

    public PlatformAuthenticatedSession require(HttpServletRequest request, String permission) {
        Object candidate = request == null ? null
                : request.getAttribute(PlatformConsoleAuthInterceptor.SESSION_REQUEST_ATTRIBUTE);
        if (!(candidate instanceof PlatformAuthenticatedSession session)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                    "live ReachAI platform login is required");
        }
        if (session.permissions() == null
                || (!session.permissions().contains(permission) && !session.permissions().contains("*"))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "platform permission is required: " + permission);
        }
        return session;
    }

    public String actor(PlatformAuthenticatedSession session) {
        if (session == null || session.user() == null
                || !StringUtils.hasText(session.user().getUsername())) {
            return "SYSTEM";
        }
        return session.user().getUsername().trim();
    }
}
