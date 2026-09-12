package com.enterprise.ai.runtime.supervisor;

import org.springframework.util.StringUtils;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Deterministic, conservative rules for the read-only page-query shortcut. No I/O or runtime state. */
final class SupervisorPageQueryPolicy {
    private SupervisorPageQueryPolicy() { }
    private static final Pattern ENUM_INTEGER_LABEL_PATTERN = Pattern.compile(
            "(?<!\\d)(\\d+)\\s*(?:[-=＞>:：]*\\s*)?([^0-9,，;；。/]+)");
    private static final Pattern WRITE_INTENT_PATTERN = Pattern.compile(
            "(?i)\\b(?:create|add|modify|update|edit|delete|remove|cancel|submit|save|pay|ship|"
                    + "enable|disable|activate|deactivate|archive|unarchive|restore)\\b");
    private static final Pattern PAGE_NUMBER_PATTERN = Pattern.compile(
            "(?i)(?:第\\s*([+-]?\\d+)\\s*页|\\b(?:pageNum|pageIndex|current|page)\\b\\s*(?:=|is|:|：)?\\s*([+-]?\\d+))");
    private static final Pattern PAGE_SIZE_PATTERN = Pattern.compile(
            "(?i)(?:每页\\s*([+-]?\\d+)\\s*条?|\\b(?:pageSize|page\\s*size)\\b\\s*(?:=|is|:|：)?\\s*([+-]?\\d+)|([+-]?\\d+)\\s*(?:条每页|per\\s+page))");

    static boolean looksLikePageReadRequest(String message) {
        return containsAny(message, "查", "查询", "统计", "多少", "哪些", "列表", "筛选", "读取", "看一下", "有几条", "总数")
                || containsAnyIgnoreCase(message, "query", "search", "list", "count", "filter", "read", "show");
    }

    static boolean hasWriteIntent(String message) {
        return containsAny(message, "新增", "创建", "修改", "更新", "删除", "取消", "提交", "保存", "付款", "发货",
                "启用", "停用", "禁用", "恢复", "归档")
                || message != null && WRITE_INTENT_PATTERN.matcher(message).find();
    }

    static boolean hasUnnegatedWriteIntent(String message) {
        String remaining = firstText(message, "");
        for (String negated : List.of(
                "不要修改", "不修改", "不要更新", "不更新", "不要删除", "不删除",
                "不要新增", "不新增", "不要创建", "不创建", "不要提交", "不提交",
                "不要保存", "不保存", "不要付款", "不付款", "不要发货", "不发货",
                "不要启用", "不启用", "不要停用", "不停用", "不要禁用", "不禁用",
                "不要恢复", "不恢复", "不要归档", "不归档")) {
            remaining = remaining.replace(negated, "");
        }
        return hasWriteIntent(remaining);
    }

    static boolean containsAny(String value, String... candidates) {
        if (value == null || candidates == null) {
            return false;
        }
        for (String candidate : candidates) {
            if (candidate != null && value.contains(candidate)) {
                return true;
            }
        }
        return false;
    }

    static boolean containsAnyIgnoreCase(String value, String... candidates) {
        if (value == null || candidates == null) {
            return false;
        }
        String normalized = value.toLowerCase(Locale.ROOT);
        for (String candidate : candidates) {
            if (candidate != null && normalized.contains(candidate.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> ruleFirstArgs(Map<String, Object> inputSchema, String message) {
        message = message == null ? "" : message;
        Map<String, Object> args = new LinkedHashMap<>();
        Object rawProperties = inputSchema == null ? null : inputSchema.get("properties");
        if (!(rawProperties instanceof Map<?, ?> properties)) {
            return args;
        }
        for (Map.Entry<?, ?> entry : properties.entrySet()) {
            String name = textObj(entry.getKey());
            if (!StringUtils.hasText(name) || !(entry.getValue() instanceof Map<?, ?> rawProperty)) {
                continue;
            }
            Map<String, Object> property = new LinkedHashMap<>();
            rawProperty.forEach((key, value) -> property.put(String.valueOf(key), value));
            String lowerName = name.toLowerCase(Locale.ROOT);
            if (isPaginationProperty(name)) {
                Integer explicitPageValue = resolvePaginationInteger(lowerName, message);
                if (explicitPageValue != null) {
                    args.put(name, explicitPageValue);
                } else if (!mentionsPaginationProperty(lowerName, message)) {
                    if (property.containsKey("default")) {
                        args.put(name, property.get("default"));
                    } else if (isPageNumberProperty(lowerName)) {
                        args.put(name, 1);
                    } else {
                        args.put(name, 10);
                    }
                }
                continue;
            }
            if (property.containsKey("default")) {
                args.put(name, property.get("default"));
                continue;
            }
            String type = textObj(property.get("type"));
            if ("integer".equalsIgnoreCase(type) || "long".equalsIgnoreCase(type)) {
                Integer value = resolveEnumInteger(name, message, textObj(property.get("description")));
                if (value != null) {
                    args.put(name, value);
                }
            }
        }
        return args;
    }

    private static Integer resolvePaginationInteger(String lowerName, String message) {
        Pattern pattern = isPageNumberProperty(lowerName) ? PAGE_NUMBER_PATTERN : PAGE_SIZE_PATTERN;
        Matcher matcher = pattern.matcher(firstText(message, ""));
        if (!matcher.find()) {
            return null;
        }
        for (int index = 1; index <= matcher.groupCount(); index++) {
            if (matcher.group(index) == null) {
                continue;
            }
            Integer parsed = parseRuleFirstInteger(matcher.group(index));
            return parsed != null && parsed > 0 ? parsed : null;
        }
        return null;
    }

    private static boolean mentionsPaginationProperty(String lowerName, String message) {
        Pattern pattern = isPageNumberProperty(lowerName) ? PAGE_NUMBER_PATTERN : PAGE_SIZE_PATTERN;
        return pattern.matcher(firstText(message, "")).find();
    }

    private static boolean isPageNumberProperty(String lowerName) {
        return lowerName.equals("pagenum") || lowerName.equals("pageindex") || lowerName.equals("current");
    }

    private static Integer resolveEnumInteger(String propertyName, String message, String description) {
        Pattern explicitPattern = Pattern.compile(
                "(?i)(?:" + integerPropertyAliases(propertyName) + ")\\s*(?:=|是|为|:|：)?\\s*(-?\\d+)");
        Matcher explicit = explicitPattern.matcher(message);
        if (explicit.find()) {
            return parseRuleFirstInteger(explicit.group(1));
        }
        if (!StringUtils.hasText(description)) {
            return null;
        }
        Matcher labels = ENUM_INTEGER_LABEL_PATTERN.matcher(description);
        String normalizedMessage = message.replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
        while (labels.find()) {
            String label = labels.group(2).trim();
            String normalizedLabel = label.replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
            if (StringUtils.hasText(normalizedLabel) && normalizedMessage.contains(normalizedLabel)) {
                return parseRuleFirstInteger(labels.group(1));
            }
        }
        return null;
    }

    private static Integer parseRuleFirstInteger(String value) {
        try {
            return Integer.valueOf(value);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static String integerPropertyAliases(String propertyName) {
        String lowerName = propertyName.toLowerCase(Locale.ROOT);
        String aliases = Pattern.quote(propertyName);
        if (lowerName.equals("status") || lowerName.equals("state") || lowerName.contains("status")) {
            return aliases + "|status|state|状态|订单状态";
        }
        if (lowerName.equals("sourcetype") || lowerName.equals("source")) {
            return aliases + "|sourceType|来源|订单来源";
        }
        if (lowerName.equals("ordertype")) {
            return aliases + "|orderType|订单类型";
        }
        return aliases;
    }

    static boolean hasMissingRequiredInput(Map<String, Object> inputSchema, Map<String, Object> args) {
        Object required = inputSchema == null ? null : inputSchema.get("required");
        if (!(required instanceof Iterable<?> names)) {
            return false;
        }
        for (Object rawName : names) {
            String name = textObj(rawName);
            if (StringUtils.hasText(name) && !args.containsKey(name)) {
                return true;
            }
        }
        return false;
    }

    static boolean hasSafePageActionMapping(Map<String, Object> mapping,
                                             Map<String, Object> inputSchema,
                                             Map<String, Object> args) {
        Map<String, Object> properties = schemaFromObject(
                inputSchema == null ? null : inputSchema.get("properties"));
        for (Map.Entry<String, Object> entry : mapping.entrySet()) {
            String name = entry.getKey();
            if (!properties.containsKey(name)
                    || !(entry.getValue() instanceof String source)
                    || !("params." + name).equals(source.trim())) {
                return false;
            }
        }
        for (String name : args.keySet()) {
            if (!(mapping.get(name) instanceof String source)
                    || !("params." + name).equals(source.trim())) {
                return false;
            }
        }
        return true;
    }

    static boolean hasUnresolvedSpecificFilter(Map<String, Object> inputSchema,
                                                Map<String, Object> args,
                                                String message) {
        Object rawProperties = inputSchema == null ? null : inputSchema.get("properties");
        if (!(rawProperties instanceof Map<?, ?> properties)) {
            return false;
        }
        for (Map.Entry<?, ?> entry : properties.entrySet()) {
            String name = textObj(entry.getKey());
            if (!StringUtils.hasText(name) || args.containsKey(name)) {
                continue;
            }
            if (isPaginationProperty(name)) {
                if (mentionsPaginationProperty(name.toLowerCase(Locale.ROOT), message)) {
                    return true;
                }
                continue;
            }
            Map<String, Object> property = schemaFromObject(entry.getValue());
            if (mentionsPropertyFilter(name, textObj(property.get("description")), message)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isPaginationProperty(String name) {
        String lowerName = name.toLowerCase(Locale.ROOT);
        return lowerName.equals("pagenum") || lowerName.equals("pageindex")
                || lowerName.equals("current") || lowerName.equals("pagesize") || lowerName.equals("size");
    }

    private static boolean mentionsPropertyFilter(String name, String description, String message) {
        String normalizedMessage = message.toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
        String lowerName = name.toLowerCase(Locale.ROOT);
        if (name.length() >= 3 && normalizedMessage.contains(lowerName.replaceAll("\\s+", ""))) {
            return true;
        }
        if ((lowerName.equals("ordersn") || lowerName.contains("orderno"))
                && containsAny(message, "订单号", "订单编号")) {
            return true;
        }
        if ((lowerName.equals("status") || lowerName.equals("state") || lowerName.contains("status"))
                && containsAnyIgnoreCase(message, "status", "state", "状态", "订单状态")) {
            return true;
        }
        if ((lowerName.equals("sourcetype") || lowerName.equals("source"))
                && containsAnyIgnoreCase(message, "sourceType", "source", "来源", "订单来源")) {
            return true;
        }
        if (lowerName.equals("ordertype")
                && containsAnyIgnoreCase(message, "orderType", "订单类型")) {
            return true;
        }
        if (lowerName.contains("receiver")
                && containsAny(message, "收货人", "手机号", "手机号码", "电话")) {
            return true;
        }
        if (lowerName.contains("time") || lowerName.contains("date")) {
            if (containsAny(message, "时间", "日期", "今天", "昨天", "本周", "本月", "最近")) {
                return true;
            }
        }
        if (lowerName.contains("keyword") && containsAny(message, "关键字", "关键词")) {
            return true;
        }
        if ((lowerName.contains("amount") || lowerName.contains("price"))
                && containsAny(message, "金额", "价格")) {
            return true;
        }
        if (lowerName.equals("id") && containsAnyIgnoreCase(message, " id", "编号", "详情")) {
            return true;
        }
        if (StringUtils.hasText(description)) {
            String label = description.split("[：:]", 2)[0].trim();
            String normalizedLabel = label.toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
            return normalizedLabel.length() >= 2 && normalizedLabel.length() <= 16
                    && normalizedMessage.contains(normalizedLabel);
        }
        return false;
    }


    private static String textObj(Object value) {
        if (value == null) return null;
        String text = String.valueOf(value).trim();
        return text.isEmpty() || "null".equalsIgnoreCase(text) ? null : text;
    }

    private static String firstText(String value, String fallback) {
        return StringUtils.hasText(value) ? value.trim() : fallback;
    }

    private static Map<String, Object> schemaFromObject(Object value) {
        if (!(value instanceof Map<?, ?> map)) return Map.of();
        Map<String, Object> result = new LinkedHashMap<>();
        map.forEach((key, item) -> result.put(String.valueOf(key), item));
        return result;
    }
}
