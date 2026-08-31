package com.enterprise.ai.control.automation;

import com.enterprise.ai.control.identity.PlatformAuthenticatedSession;
import com.enterprise.ai.control.identity.PlatformPermissions;
import com.enterprise.ai.control.identity.PlatformRequestAuthorization;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

@Component
@RequiredArgsConstructor
final class AutomationManagementAccess {

    static final String READ = PlatformPermissions.AUTOMATION_READ;
    static final String WRITE = PlatformPermissions.AUTOMATION_WRITE;
    static final String OPERATE = PlatformPermissions.AUTOMATION_OPERATE;

    private final PlatformRequestAuthorization requestAuthorization;

    PlatformAuthenticatedSession require(HttpServletRequest request, String permission) {
        return requestAuthorization.requirePermission(request, permission);
    }

    void requireProject(PlatformAuthenticatedSession session, String permission, String projectCode) {
        if (session != null && session.hasGlobalPermission(permission)) {
            return;
        }
        if (!StringUtils.hasText(projectCode)
                || session == null
                || !session.hasResourcePermission(permission, "PROJECT", null, projectCode.trim())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "platform permission is required for the Automation project: " + permission);
        }
    }

    String projectCode(Object payload) {
        if (!(payload instanceof Map<?, ?> root)) return null;
        Object summary = root.containsKey("automation") ? root.get("automation") : root.get("summary");
        Map<?, ?> source = summary instanceof Map<?, ?> map ? map : root;
        Object value = source.get("projectCode");
        return value == null ? null : String.valueOf(value).trim();
    }

    String actorId(PlatformAuthenticatedSession session) {
        if (session == null || session.user() == null || session.user().getId() == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                    "attested platform user id is required");
        }
        return "platform-user:" + session.user().getId();
    }
}
