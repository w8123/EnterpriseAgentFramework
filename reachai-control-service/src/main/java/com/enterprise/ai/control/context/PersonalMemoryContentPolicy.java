package com.enterprise.ai.control.context;

import org.springframework.http.HttpStatus;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

import java.util.regex.Pattern;

/** Shared size and secret-safety policy for explicit memories and passive candidates. */
final class PersonalMemoryContentPolicy {

    static final int MAX_CONTENT_CHARS = 16_000;
    private static final Pattern SECRET_ASSIGNMENT = Pattern.compile(
            "(?i)(password|passwd|secret|api[_-]?key|access[_-]?token|refresh[_-]?token)\\s*[:=]\\s*[^\\s,;]{6,}");
    private static final Pattern PRIVATE_KEY = Pattern.compile("-----BEGIN [A-Z ]*PRIVATE KEY-----");
    private static final Pattern WELL_KNOWN_TOKEN = Pattern.compile(
            "(?i)\\b(sk-[A-Za-z0-9_-]{20,}|gh[pousr]_[A-Za-z0-9_]{20,}|AKIA[0-9A-Z]{16})\\b");

    static String validate(String value) {
        String content = normalize(value);
        if (!StringUtils.hasText(content)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "personal memory content is required");
        }
        if (content.length() > MAX_CONTENT_CHARS) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "personal memory content exceeds " + MAX_CONTENT_CHARS + " characters");
        }
        if (!isSafe(content)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "credentials and secrets cannot be stored in personal memory");
        }
        return content;
    }

    static boolean isSafe(String value) {
        String content = normalize(value);
        return StringUtils.hasText(content)
                && content.length() <= MAX_CONTENT_CHARS
                && !SECRET_ASSIGNMENT.matcher(content).find()
                && !PRIVATE_KEY.matcher(content).find()
                && !WELL_KNOWN_TOKEN.matcher(content).find();
    }

    private static String normalize(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private PersonalMemoryContentPolicy() {
    }
}
