package com.enterprise.ai.control.memory;

import org.springframework.http.HttpStatus;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

/**
 * The layer a memory lives in: the owner's personal layer, used everywhere, or one project's layer,
 * used only when the AI works on that project. Stored as {@code personal} or {@code project:<code>}.
 */
public record PersonalMemoryScope(String key, String projectCode) {

    public static final String PERSONAL_KEY = "personal";
    private static final String PROJECT_PREFIX = "project:";
    public static final PersonalMemoryScope PERSONAL = new PersonalMemoryScope(PERSONAL_KEY, null);

    public static PersonalMemoryScope of(String projectCode) {
        String code = StringUtils.hasText(projectCode) ? projectCode.trim() : null;
        if (code == null) {
            return PERSONAL;
        }
        if (code.length() > 96 || !code.matches("[A-Za-z0-9._:-]+")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid project code");
        }
        return new PersonalMemoryScope(PROJECT_PREFIX + code, code);
    }

    /** Parses a list filter: blank means every layer, {@code personal} or {@code project:<code>} one layer. */
    public static PersonalMemoryScope parseFilter(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        String trimmed = value.trim();
        if (PERSONAL_KEY.equalsIgnoreCase(trimmed)) {
            return PERSONAL;
        }
        if (trimmed.startsWith(PROJECT_PREFIX)) {
            return of(trimmed.substring(PROJECT_PREFIX.length()));
        }
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "scope must be personal or project:<code>");
    }

    public boolean personal() {
        return projectCode == null;
    }

    public String layer() {
        return personal() ? "PERSONAL" : "PROJECT";
    }
}
