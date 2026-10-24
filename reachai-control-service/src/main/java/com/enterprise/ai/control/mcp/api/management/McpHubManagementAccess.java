package com.enterprise.ai.control.mcp.api.management;

import com.enterprise.ai.control.identity.PlatformAuthenticatedSession;
import com.enterprise.ai.control.identity.PlatformConsoleAuthInterceptor;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

@Component
public class McpHubManagementAccess {

    public static final String READ = "mcp-hub:read";
    public static final String MANAGE_PUBLICATIONS = "mcp-hub:publication:manage";
    public static final String MANAGE_IRREVERSIBLE = "mcp-hub:publication:irreversible";
    public static final String MANAGE_CREDENTIALS = "mcp-hub:credential:manage";
    public static final String MANAGE_CREDENTIAL_ROLES = "mcp-hub:credential-role:manage";
    public static final String READ_PAYLOAD = "mcp-hub:payload:read";
    public static final String MANAGE_EMPLOYEE_CONNECTIONS = "mcp-hub:employee-connection:manage";

    public PlatformAuthenticatedSession require(HttpServletRequest request, String permission) {
        return requireAny(request, permission);
    }

    /** Admits a caller holding at least one of {@code permissions}. */
    public PlatformAuthenticatedSession requireAny(HttpServletRequest request, String... permissions) {
        Object candidate = request == null ? null
                : request.getAttribute(PlatformConsoleAuthInterceptor.SESSION_REQUEST_ATTRIBUTE);
        if (!(candidate instanceof PlatformAuthenticatedSession session)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                    "live ReachAI platform login is required");
        }
        boolean granted = session.permissions() != null
                && (session.permissions().contains("*")
                || java.util.Arrays.stream(permissions).anyMatch(session.permissions()::contains));
        if (!granted) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "platform permission is required: " + String.join(" or ", permissions));
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
