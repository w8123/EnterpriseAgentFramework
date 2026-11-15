package com.enterprise.ai.runtime.supervisor;

import org.springframework.util.StringUtils;

import java.util.List;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/** Recognition only: this never authorizes or executes a page action. */
final class TrustedPageDraftIntent {
    private static final Pattern MUTATION = Pattern.compile(
            "(?i)(修改|更改|更新|填写|填入|填上|改成|改为|改一下|帮我改|替我改|请改|"
                    + "\\b(?:update|edit|fill|change)\\b)");
    private static final Pattern NO_ACTION = Pattern.compile(
            "(?i)(不要|请勿|禁止|无需|无须|不需要|不必|不得|不能|不可|不许|不允许|不想|不愿|不希望|"
                    + "不(?:修改|更改|更新|填写|填入|填上|改|操作|保存|提交)|别|勿|"
                    + "如果|假如|假设|例如|比如|怎么|如何|怎样|是什么意思|说明|解释|教程|步骤|"
                    + "\\b(?:do\\s+not|don't|never|without|if|how|explain|describe)\\b)");
    private static final Pattern EXPLICIT_ITEM = Pattern.compile(
            "(?:^|[\\s,，;；])([\\p{IsHan}]{2,8}|[A-Za-z][A-Za-z_]{0,31})[ \\t]*[:：=][ \\t]*([^\\s,，;；]+)");
    private static final Pattern ADJACENT_DIGITS = Pattern.compile(
            "(?:^|[\\s,，;；])([\\p{IsHan}]{2,8}|[A-Za-z][A-Za-z_]{0,31})[ \\t]*(?=\\d{3,}(?:$|[\\s,，;；。.!！?？]))");
    private static final Pattern INSTRUCTION_PREFIX = Pattern.compile("^(?:请|将|把|另外|还有|然后|也|再|并|给|帮|我的|我想|我)");

    private TrustedPageDraftIntent() { }

    static boolean matches(String message, List<String> approvedAliases) {
        if (!StringUtils.hasText(message) || approvedAliases == null || approvedAliases.isEmpty()) return false;
        String unquoted = message.replaceAll("“[^”]*”|\"[^\"]*\"", "");
        if (NO_ACTION.matcher(unquoted).find() || !MUTATION.matcher(unquoted).find()) return false;
        String text = unquoted.toLowerCase(Locale.ROOT);
        return approvedAliases.stream().filter(StringUtils::hasText).map(String::trim)
                .filter(alias -> alias.codePointCount(0, alias.length()) >= 2 && alias.length() <= 80)
                .map(alias -> alias.toLowerCase(Locale.ROOT)).anyMatch(text::contains);
    }

    /** Require each exact value next to its approved schema label; ambiguous text fails closed. */
    static boolean valuesAppearInMessage(String message, Map<String, Object> args, Map<String, String> approvedLabels) {
        if (!StringUtils.hasText(message) || args == null || approvedLabels == null) return false;
        try {
            for (var entry : args.entrySet()) {
                Object value = entry.getValue();
                if (!(value instanceof String || value instanceof Number || value instanceof Boolean)) return false;
                String literal = String.valueOf(value);
                if (!StringUtils.hasText(literal) || !literal.equals(literal.trim())) return false;
                String label = approvedLabels.get(entry.getKey());
                if (!StringUtils.hasText(label) || label.length() > 40 || !label.equals(label.trim())) return false;
                int first = message.indexOf(label);
                if (first < 0 || first != message.lastIndexOf(label)) return false;
                String pattern = Pattern.quote(label)
                        + "(?:\\s*(?:修改为|更新为|设置为|填写为|改成|改为|为|是|[:：=])\\s*|\\s*)"
                        + "[\"“']?" + Pattern.quote(literal) + "[\"”']?(?=$|[\\s,，;；。.!！?？])";
                if (!Pattern.compile(pattern).matcher(message).find()) return false;
            }
            return true;
        } catch (Exception invalid) {
            return false;
        }
    }

    /** Only report literal labels of explicitly structured remaining items; never retain values. */
    static List<String> unhandledItemLabels(String message, Map<String, Object> args, Map<String, Object> pin) {
        if (!StringUtils.hasText(message) || message.indexOf('<') >= 0 || message.indexOf('>') >= 0
                || args == null || pin == null
                || !(pin.get("declaration") instanceof Map<?, ?> declaration)
                || !(declaration.get("fieldLabels") instanceof Map<?, ?> labels)) return List.of();
        String remaining = message;
        for (var entry : args.entrySet()) {
            Object rawLabel = labels.get(entry.getKey());
            Object value = entry.getValue();
            if (!(rawLabel instanceof String label) || !(value instanceof String || value instanceof Number || value instanceof Boolean))
                return List.of();
            String expression = Pattern.quote(label)
                    + "(?:\\s*(?:修改为|更新为|设置为|填写为|改成|改为|为|是|[:：=])\\s*|\\s*)"
                    + "[\"“']?" + Pattern.quote(String.valueOf(value)) + "[\"”']?(?=$|[\\s,，;；。.!！?？])";
            remaining = Pattern.compile(expression).matcher(remaining).replaceFirst(" ");
        }
        LinkedHashSet<String> found = new LinkedHashSet<>();
        for (Pattern pattern : List.of(EXPLICIT_ITEM, ADJACENT_DIGITS)) {
            var matches = pattern.matcher(remaining);
            while (matches.find() && found.size() < 8) {
                String label = matches.group(1);
                // A phrase containing an instruction or a reviewed label is not an unambiguous item label.
                if (MUTATION.matcher(label).find() || NO_ACTION.matcher(label).find() || INSTRUCTION_PREFIX.matcher(label).find()
                        || labels.values().stream().anyMatch(known -> known instanceof String s && !s.isBlank()
                                && (label.contains(s) || s.contains(label)))) continue;
                found.add(label);
            }
        }
        return List.copyOf(found);
    }
}
