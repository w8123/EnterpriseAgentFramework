package com.enterprise.ai.runtime.execution;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.StringJoiner;

/** Verifies that the stored approval text actually exposes every proposed argument. */
public final class SupervisorApprovalPresentation {
    private SupervisorApprovalPresentation() { }

    public static boolean complete(Object arguments, Map<String, Object> uiRequest) {
        if (!(arguments instanceof Map<?, ?> args)) return false;
        Map<String, Object> expected = new LinkedHashMap<>();
        if (!collect(expected, "", args, 0)) return false;
        if (!(uiRequest.get("data") instanceof Map<?, ?> data)
                || !(data.get("arguments") instanceof Map<?, ?> shown)
                || !expected.equals(shown)) return false;
        if (expected.isEmpty()) return true;
        StringJoiner suffix = new StringJoiner("，", "。操作参数：", "");
        expected.forEach((key, value) -> suffix.add(key + "=" + value));
        return uiRequest.get("message") instanceof String message && message.endsWith(suffix.toString());
    }

    /** A page approval must display the same approved declaration as its durable pin. */
    public static boolean pageDeclarationComplete(Object rawPin, Map<String, Object> uiRequest) {
        if (rawPin == null) return true; // Existing non-page approvals have no page pin.
        if (!(rawPin instanceof Map<?, ?> pin) || !(pin.get("declaration") instanceof Map<?, ?> declaration)
                || !(uiRequest.get("data") instanceof Map<?, ?> data)) return false;
        Object target = declaration.get("targetAlias");
        Object title = declaration.get("actionTitle");
        Object description = declaration.get("actionDescription");
        String descriptionText = description == null ? "未提供" : description instanceof String text ? text : null;
        Object effect = declaration.get("effect");
        if (!(declaration.get("fieldLabels") instanceof Map<?, ?> labels)
                || !(data.get("arguments") instanceof Map<?, ?> args)) return false;
        if (!(target instanceof String targetText) || targetText.isBlank() || targetText.length() > 80
                || !(title instanceof String titleText) || titleText.isBlank() || titleText.length() > 100
                || descriptionText == null || descriptionText.length() > 500
                || !"PAGE_LOCAL_DRAFT".equals(effect)
                || !target.equals(data.get("pageTarget")) || !title.equals(data.get("pageActionTitle"))
                || !descriptionText.equals(data.get("pageActionDescription")) || !effect.equals(data.get("pageEffect"))
                || !("确认页面动作：" + title).equals(uiRequest.get("title"))) return false;
        String prefix = pageExplanation(targetText, titleText, descriptionText, labels, args);
        if (prefix == null) return false;
        StringJoiner suffix = new StringJoiner("，", "。操作参数：", "");
        args.forEach((key, value) -> suffix.add(key + "=" + value));
        String expectedMessage = args.isEmpty() ? prefix : prefix + suffix;
        return uiRequest.get("message") instanceof String message && message.equals(expectedMessage);
    }

    public static String pageExplanation(String target, String title, String description,
                                         Map<?, ?> labels, Map<?, ?> args) {
        StringJoiner fields = new StringJoiner("、");
        for (Object rawKey : args.keySet()) {
            if (!(rawKey instanceof String key) || !(labels.get(key) instanceof String label)
                    || label.isBlank() || label.length() > 40) return null;
            fields.add(label + "（" + key + "）");
        }
        return "业务目标：" + target + "；页面动作：" + title + "；说明：" + description
                + "；本次仅处理这些字段：" + fields + "；未列出的内容未处理；业务方声明此动作不会主动提交，但业务页面仍可能自动保存，请核对";
    }

    private static boolean collect(Map<String, Object> out, String path, Object value, int depth) {
        if (depth > 5 || out.size() >= 30) return false;
        if (value instanceof Map<?, ?> map) {
            if (map.isEmpty() && !path.isEmpty()) return add(out, path, "{}");
            for (var entry : map.entrySet()) {
                if (!(entry.getKey() instanceof String raw)) return false;
                String key = raw.trim();
                if (key.isEmpty() || sensitive(key)) return false;
                String child = path.isEmpty() ? key : path + "." + key;
                if (!collect(out, child, entry.getValue(), depth + 1)) return false;
            }
            return true;
        }
        if (value instanceof List<?> list) {
            if (list.isEmpty()) return add(out, path, "[]");
            for (int i = 0; i < list.size(); i++) {
                if (!collect(out, path + "[" + i + "]", list.get(i), depth + 1)) return false;
            }
            return true;
        }
        if (value == null || value instanceof String || value instanceof Number || value instanceof Boolean) {
            String rendered = String.valueOf(value);
            return rendered.length() <= 200 && add(out, path, rendered);
        }
        return false;
    }

    private static boolean add(Map<String, Object> out, String path, String value) {
        if (out.containsKey(path)) return false;
        out.put(path, value);
        return out.size() < 30;
    }

    private static boolean sensitive(String key) {
        String lower = key.toLowerCase(Locale.ROOT);
        return lower.contains("secret") || lower.contains("token") || lower.contains("password")
                || lower.contains("authorization") || lower.contains("credential") || lower.contains("apikey")
                || lower.contains("api_key") || lower.contains("cookie");
    }
}
