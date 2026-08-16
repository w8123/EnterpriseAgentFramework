package com.enterprise.ai.control.context;

import org.springframework.http.HttpStatus;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

import java.util.Locale;

/**
 * Keeps private RUNTIME_USER memory behind the owner-scoped personal-memory API.
 * Generic Context governance routes are deliberately not a privileged backdoor.
 */
final class PersonalMemoryRouteBoundary {

    static final String RUNTIME_USER = "RUNTIME_USER";

    private PersonalMemoryRouteBoundary() {
    }

    static boolean isRuntimeUserLane(String memoryLane) {
        return RUNTIME_USER.equals(upper(memoryLane));
    }

    static boolean isPersonalNamespace(ContextNamespaceEntity namespace) {
        return namespace != null && RUNTIME_USER.equals(upper(namespace.getOwnerType()));
    }

    static boolean isPersonalItem(ContextItemEntity item) {
        return item != null && isRuntimeUserLane(item.getMemoryLane());
    }

    static boolean isPersonalAudit(ContextAuditEventEntity event) {
        return event != null && RUNTIME_USER.equals(upper(event.getActorType()));
    }

    static void rejectRuntimeUserLane(String memoryLane) {
        if (isRuntimeUserLane(memoryLane)) {
            throw forbidden();
        }
    }

    static void rejectPersonalNamespace(ContextNamespaceEntity namespace) {
        if (isPersonalNamespace(namespace)) {
            throw forbidden();
        }
    }

    static ResponseStatusException forbidden() {
        return new ResponseStatusException(HttpStatus.FORBIDDEN,
                "Private personal memory is only available through the owner-scoped personal-memory API");
    }

    private static String upper(String value) {
        return StringUtils.hasText(value) ? value.trim().toUpperCase(Locale.ROOT) : null;
    }
}
