package com.enterprise.ai.runtime.execution.kernel;

import com.enterprise.ai.runtime.client.control.RuntimeControlCatalogClient.PageBridgeExecutionResponse;
import com.enterprise.ai.runtime.execution.RuntimeGraphSpecExecutionResult;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Summarizes safe page-action outcomes and stores their immutable run history. */
final class RuntimePageActionResults {
    private RuntimePageActionResults() { }

    /** Keeps only action state, an aggregate count, and an optional short business message. */
    static Map<String, Object> pageActionResultSummary(String actionKey,
                                                        PageBridgeExecutionResponse response) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("actionKey", actionKey);
        summary.put("success", response.success());
        String status = firstText(response.status(), response.success() ? "SUCCESS" : "FAILED");
        summary.put("status", status);
        if (isBusinessTerminalPageAction(response)) {
            summary.put("outcomeClass", "BUSINESS_TERMINAL");
            summary.put("businessOutcome", status);
        }
        Long total = null;
        String message = null;
        for (Map<String, Object> payload : pageActionPayloadCandidates(mapValue(response.data()))) {
            if (!StringUtils.hasText(message)) message = safePageActionMessage(payload.get("message"));
            if (total == null) total = pageActionCount(payload);
        }
        if (StringUtils.hasText(message)) {
            summary.put("message", message);
        } else if (isBusinessTerminalPageAction(response)) {
            summary.put("message", pageActionBusinessMessage(response));
        }
        if (total != null) {
            summary.put("total", total);
            summary.put("empty", total == 0L);
        }
        if (pageActionPayloadCandidates(mapValue(response.data())).stream()
                .anyMatch(payload -> Boolean.TRUE.equals(payload.get("userConfirmed")))) {
            summary.put("userConfirmed", true);
        }
        return Map.copyOf(summary);
    }

    static boolean isBusinessTerminalPageAction(PageBridgeExecutionResponse response) {
        if (response == null || !response.success()) return false;
        if ("PAGE_BRIDGE_BUSINESS_TERMINAL".equalsIgnoreCase(response.code())) return true;
        String status = text(response.status());
        return "NO_DATA".equalsIgnoreCase(status)
                || "PRECONDITION_FAILED".equalsIgnoreCase(status)
                || "USER_CANCELLED".equalsIgnoreCase(status)
                || "CANCELLED".equalsIgnoreCase(status);
    }

    static String pageActionBusinessMessage(PageBridgeExecutionResponse response) {
        for (Map<String, Object> payload : pageActionPayloadCandidates(
                mapValue(response == null ? null : response.data()))) {
            String message = safePageActionMessage(payload.get("message"));
            if (StringUtils.hasText(message)) return message;
        }
        String status = response == null ? null : text(response.status());
        if ("NO_DATA".equalsIgnoreCase(status)) return "未查询到符合条件的数据。";
        if ("PRECONDITION_FAILED".equalsIgnoreCase(status)) {
            return "当前业务状态不满足执行该页面操作的条件。";
        }
        if ("USER_CANCELLED".equalsIgnoreCase(status) || "CANCELLED".equalsIgnoreCase(status)) {
            return "已取消本次页面操作。";
        }
        return "页面操作已结束，未产生可继续执行的业务结果。";
    }

    private static List<Map<String, Object>> pageActionPayloadCandidates(Map<String, Object> root) {
        if (root == null || root.isEmpty()) return List.of();
        List<Map<String, Object>> candidates = new ArrayList<>();
        candidates.add(root);
        for (int index = 0; index < candidates.size() && index < 4; index++) {
            Map<String, Object> candidate = candidates.get(index);
            for (String nestedKey : List.of("data", "result", "payload")) {
                Map<String, Object> nested = mapValue(candidate.get(nestedKey));
                if (nested != null && !candidates.contains(nested)) candidates.add(nested);
            }
        }
        return candidates;
    }

    private static Long pageActionCount(Map<String, Object> payload) {
        for (String countKey : List.of("total", "totalCount", "rowCount", "count")) {
            Long count = nonNegativeIntegralCount(payload.get(countKey));
            if (count != null) return count;
        }
        for (String collectionKey : List.of("records", "rows", "items", "list", "courses")) {
            Object value = payload.get(collectionKey);
            if (value instanceof Collection<?> collection) return (long) collection.size();
        }
        return null;
    }

    private static Long nonNegativeIntegralCount(Object value) {
        if (value instanceof Number number) {
            double decimal = number.doubleValue();
            long integral = number.longValue();
            return Double.isFinite(decimal) && decimal == integral && integral >= 0 ? integral : null;
        }
        if (value instanceof CharSequence sequence) {
            String text = sequence.toString().trim();
            if (text.matches("\\d+")) {
                try {
                    return Long.parseLong(text);
                } catch (NumberFormatException ignored) {
                    return null;
                }
            }
        }
        return null;
    }

    private static String safePageActionMessage(Object value) {
        if (!(value instanceof CharSequence sequence)) return null;
        String message = sequence.toString().trim();
        if (!StringUtils.hasText(message)) return null;
        return message.length() <= 240 ? message : message.substring(0, 240);
    }

    static void rememberPageActionResult(Map<String, Object> context,
                                          RuntimeGraphSpecExecutionResult result) {
        if (result.metadata() == null) {
            return;
        }
        Map<String, Object> summary = mapValue(result.metadata().get("pageActionResultSummary"));
        if (summary == null || summary.isEmpty()) {
            return;
        }
        List<Map<String, Object>> history = new ArrayList<>();
        Object existing = context.get("pageActionResults");
        if (existing instanceof List<?> items) {
            for (Object item : items) {
                Map<String, Object> previous = mapValue(item);
                if (previous != null && !previous.isEmpty()) {
                    history.add(Map.copyOf(previous));
                }
            }
        }
        history.add(Map.copyOf(summary));
        context.put("pageActionResults", List.copyOf(history));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> mapValue(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : null;
    }

    private static String text(Object value) {
        if (value == null) return null;
        String text = String.valueOf(value);
        return StringUtils.hasText(text) ? text.trim() : null;
    }

    private static String firstText(String... values) {
        for (String value : values) {
            if (StringUtils.hasText(value)) return value.trim();
        }
        return null;
    }

}
