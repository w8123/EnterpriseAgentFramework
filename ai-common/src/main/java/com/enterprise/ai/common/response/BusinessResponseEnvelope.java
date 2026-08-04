package com.enterprise.ai.common.response;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;

/**
 * Interprets the conventional top-level business response envelope used by
 * enterprise APIs. Transport success alone is not enough when the payload
 * explicitly reports a non-200 business code or {@code success=false}.
 */
public final class BusinessResponseEnvelope {

    public static final String FAILURE_MESSAGE = "查询失败";

    private static final int MAX_DETAIL_LENGTH = 240;

    private BusinessResponseEnvelope() {
    }

    public static Optional<Failure> failure(Object payload) {
        if (!(payload instanceof Map<?, ?> body)) {
            return Optional.empty();
        }
        boolean hasCode = body.containsKey("code");
        Object code = body.get("code");
        boolean codeFailed = hasCode && !isSuccessCode(code);
        boolean successFailed = Boolean.FALSE.equals(booleanValue(body.get("success")));
        if (!codeFailed && !successFailed) {
            return Optional.empty();
        }
        return Optional.of(new Failure(text(code), failureMessage(body)));
    }

    private static boolean isSuccessCode(Object code) {
        if (code == null) {
            return false;
        }
        try {
            return new BigDecimal(String.valueOf(code).trim()).compareTo(BigDecimal.valueOf(200)) == 0;
        } catch (NumberFormatException ex) {
            return false;
        }
    }

    private static Boolean booleanValue(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value instanceof String text) {
            if ("true".equalsIgnoreCase(text.trim())) {
                return Boolean.TRUE;
            }
            if ("false".equalsIgnoreCase(text.trim())) {
                return Boolean.FALSE;
            }
        }
        return null;
    }

    private static String failureMessage(Map<?, ?> body) {
        String detail = firstText(body.get("message"), body.get("msg"), body.get("error"));
        if (detail == null || FAILURE_MESSAGE.equals(detail)) {
            return FAILURE_MESSAGE;
        }
        if (detail.startsWith(FAILURE_MESSAGE + "：") || detail.startsWith(FAILURE_MESSAGE + ":")) {
            return detail;
        }
        return FAILURE_MESSAGE + "：" + detail;
    }

    private static String firstText(Object... values) {
        for (Object value : values) {
            String normalized = text(value);
            if (normalized != null) {
                return normalized;
            }
        }
        return null;
    }

    private static String text(Object value) {
        if (value == null) {
            return null;
        }
        String normalized = String.valueOf(value)
                .replace('\r', ' ')
                .replace('\n', ' ')
                .trim();
        if (normalized.isEmpty()) {
            return null;
        }
        return normalized.length() <= MAX_DETAIL_LENGTH
                ? normalized
                : normalized.substring(0, MAX_DETAIL_LENGTH);
    }

    public record Failure(String businessCode, String message) {
    }
}
