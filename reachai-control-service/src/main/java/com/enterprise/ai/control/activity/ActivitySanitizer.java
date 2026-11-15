package com.enterprise.ai.control.activity;

import java.net.URI;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Server-side second pass over what a business system reported. SDKs already mask, but ReachAI never trusts
 * that: personal data in search words and error messages is masked here again before anything is stored.
 */
public final class ActivitySanitizer {

    private static final Pattern EMAIL = Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");
    private static final Pattern ID_CARD = Pattern.compile("(?<!\\d)\\d{17}[\\dXx](?!\\d)");
    private static final Pattern MOBILE = Pattern.compile("(?<!\\d)(1[3-9]\\d)\\d{4}(\\d{4})(?!\\d)");
    private static final Pattern LONG_NUMBER = Pattern.compile("(?<!\\d)\\d{12,}(?!\\d)");
    private static final Pattern SECRET = Pattern.compile(
            "(?i)(password|passwd|pwd|token|secret|authorization|bearer|密码|口令)(\\s*[=:：]\\s*|\\s+)\\S+");
    private static final Pattern ID_SEGMENT = Pattern.compile(
            "^(\\d+|[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}|[0-9a-fA-F]{24,})$");
    private static final Set<String> METHODS = Set.of("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS");

    private ActivitySanitizer() {
    }

    public static String text(String value, int maxLength) {
        if (value == null) return null;
        String cleaned = value.codePoints()
                .map(c -> Character.isISOControl(c) ? ' ' : c)
                .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append)
                .toString().replaceAll("\\s+", " ").trim();
        if (cleaned.isEmpty()) return null;
        cleaned = SECRET.matcher(cleaned).replaceAll("$1$2***");
        cleaned = EMAIL.matcher(cleaned).replaceAll("***@***");
        cleaned = ID_CARD.matcher(cleaned).replaceAll("******************");
        cleaned = MOBILE.matcher(cleaned).replaceAll("$1****$2");
        cleaned = LONG_NUMBER.matcher(cleaned).replaceAll("************");
        return truncate(cleaned, maxLength);
    }

    /** "post /admin-api/erp/customer/12/update" → "POST /admin-api/erp/customer/{id}/update". */
    public static String operationKey(String value) {
        String text = plain(value, 600);
        if (text == null) return null;
        String method = null;
        String path = text;
        int space = text.indexOf(' ');
        if (space > 0) {
            String candidate = text.substring(0, space).toUpperCase(Locale.ROOT);
            if (METHODS.contains(candidate)) {
                method = candidate;
                path = text.substring(space + 1).trim();
            }
        }
        path = path(path);
        if (path == null) return null;
        return truncate(method == null ? path : method + " " + path, 300);
    }

    /** Page or endpoint path only: no scheme, host, query or fragment; ids collapse to {id}. */
    public static String path(String value) {
        String text = plain(value, 1000);
        if (text == null) return null;
        if (text.contains("://")) {
            try {
                text = URI.create(text.replace(" ", "%20")).getRawPath();
            } catch (IllegalArgumentException invalid) {
                return null;
            }
        }
        if (text == null) return null;
        int cut = indexOfAny(text, '?', '#');
        if (cut >= 0) text = text.substring(0, cut);
        if (text.isEmpty()) return null;
        if (!text.startsWith("/")) text = "/" + text;
        StringBuilder out = new StringBuilder();
        for (String segment : text.split("/")) {
            if (segment.isEmpty()) continue;
            out.append('/').append(ID_SEGMENT.matcher(segment).matches() ? "{id}" : segment);
        }
        String normalized = out.length() == 0 ? "/" : out.toString();
        return truncate(normalized, 300);
    }

    public static String plain(String value, int maxLength) {
        if (value == null) return null;
        String cleaned = value.codePoints()
                .map(c -> Character.isISOControl(c) ? ' ' : c)
                .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append)
                .toString().trim();
        return cleaned.isEmpty() ? null : truncate(cleaned, maxLength);
    }

    static String truncate(String value, int maxLength) {
        if (value == null || value.codePointCount(0, value.length()) <= maxLength) return value;
        return value.substring(0, value.offsetByCodePoints(0, maxLength));
    }

    private static int indexOfAny(String text, char... chars) {
        int best = -1;
        for (char c : chars) {
            int index = text.indexOf(c);
            if (index >= 0 && (best < 0 || index < best)) best = index;
        }
        return best;
    }
}
