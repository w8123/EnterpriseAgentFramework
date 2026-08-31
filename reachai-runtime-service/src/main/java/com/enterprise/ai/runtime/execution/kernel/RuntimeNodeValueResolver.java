package com.enterprise.ai.runtime.execution.kernel;

import com.enterprise.ai.runtime.execution.context.WorkflowVariableNamespaces;
import org.springframework.util.StringUtils;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Resolves Runtime variables and templates with one shared semantic contract.
 *
 * <p>Node handlers must use this component instead of growing private variants. In particular,
 * structured {@code input} remains addressable by path while generic user-input text falls back to
 * the transport message, and bare business variables resolve through the {@code var} namespace.</p>
 */
final class RuntimeNodeValueResolver {

    private static final Pattern TEMPLATE_TOKEN = Pattern.compile("\\{\\{\\s*([A-Za-z0-9_.-]+)\\s*}}");

    Object renderInputValue(Object value, Map<String, Object> context) {
        if (value instanceof Map<?, ?> rawMap) {
            Map<String, Object> rendered = new LinkedHashMap<>();
            rawMap.forEach((key, item) -> rendered.put(String.valueOf(key), renderInputValue(item, context)));
            return rendered;
        }
        if (value instanceof List<?> rawList) {
            return rawList.stream().map(item -> renderInputValue(item, context)).toList();
        }
        if (value instanceof String template) {
            if (!template.contains("{{") && isContextExpression(template, context)) {
                return resolveContextValue(template, context);
            }
            return renderTemplate(template, context);
        }
        return value;
    }

    String renderTemplate(String template, Map<String, Object> context) {
        if (!StringUtils.hasText(template)) {
            return null;
        }
        Matcher matcher = TEMPLATE_TOKEN.matcher(template);
        StringBuffer rendered = new StringBuffer();
        while (matcher.find()) {
            String replacement = resolveToken(matcher.group(1), context);
            matcher.appendReplacement(rendered, Matcher.quoteReplacement(replacement == null ? "" : replacement));
        }
        matcher.appendTail(rendered);
        return rendered.toString();
    }

    /**
     * The page-embed transport may attach an empty structured {@code input} object while carrying
     * the natural-language request in {@code message}. Structured input remains available through
     * explicit paths such as {@code input.orderNo}.
     */
    String userInputText(Map<String, Object> context) {
        String userInput = context == null ? null : text(context.get("userInput"));
        if (StringUtils.hasText(userInput)) {
            return userInput;
        }
        Object rawInput = context == null ? null : context.get("input");
        if (!(rawInput instanceof Map<?, ?>) && !(rawInput instanceof Collection<?>)) {
            String input = text(rawInput);
            if (StringUtils.hasText(input)) {
                return input;
            }
        }
        String message = context == null ? null : text(context.get("message"));
        if (StringUtils.hasText(message)) {
            return message;
        }
        if (isEmptyStructuredInput(rawInput)) {
            return "";
        }
        return firstText(text(rawInput), "");
    }

    Object resolveContextValue(String expression, Map<String, Object> context) {
        String path = expression == null ? "" : expression.trim();
        if (path.startsWith("$.")) {
            path = path.substring(2);
        }
        Object value = context.get(path);
        if (value == null && ("query".equals(path) || "userInput".equals(path))) {
            value = userInputText(context);
        }
        // Bare business aliases resolve through var.<alias> without requiring a second write.
        if (value == null && StringUtils.hasText(path) && !path.contains(".")
                && !WorkflowVariableNamespaces.isReservedRoot(path)) {
            value = context.get(WorkflowVariableNamespaces.VAR_ROOT + "." + path);
            if (value == null && context.get(WorkflowVariableNamespaces.VAR_ROOT) instanceof Map<?, ?> variables) {
                value = variables.get(path);
            }
        }
        if (value == null && path.contains(".")) {
            Object current = context;
            for (String part : path.split("\\.")) {
                if (!(current instanceof Map<?, ?> map)) {
                    current = null;
                    break;
                }
                current = map.get(part);
            }
            value = current;
        }
        return value;
    }

    private String resolveToken(String token, Map<String, Object> context) {
        String resolved = switch (token) {
            case "input", "userInput", "query" -> userInputText(context);
            case "message" -> firstText(text(context.get("message")), userInputText(context));
            case "lastOutput", "previousOutput" ->
                    firstText(text(context.get(token)), userInputText(context));
            default -> text(resolveContextValue(token, context));
        };
        return resolved == null ? "" : resolved;
    }

    private boolean isContextExpression(String value, Map<String, Object> context) {
        if (!StringUtils.hasText(value)) {
            return false;
        }
        String candidate = value.trim();
        if (context.containsKey(candidate)) {
            return true;
        }
        if (candidate.startsWith("$.")) {
            candidate = candidate.substring(2);
        }
        int separator = candidate.indexOf('.');
        String root = separator < 0 ? candidate : candidate.substring(0, separator);
        boolean bareBusinessVariable = separator < 0
                && context.get(WorkflowVariableNamespaces.VAR_ROOT) instanceof Map<?, ?> variables
                && variables.containsKey(candidate);
        return context.get(root) instanceof Map<?, ?>
                || candidate.startsWith("params.")
                || candidate.startsWith("nodeOutput.")
                || candidate.startsWith("var.")
                || candidate.startsWith("sys.")
                || "input".equals(candidate)
                || "message".equals(candidate)
                || "lastOutput".equals(candidate)
                || "previousOutput".equals(candidate)
                || (StringUtils.hasText(root)
                && !WorkflowVariableNamespaces.isReservedRoot(root)
                && (context.containsKey(WorkflowVariableNamespaces.VAR_ROOT + "." + candidate)
                || bareBusinessVariable));
    }

    private boolean isEmptyStructuredInput(Object value) {
        return (value instanceof Map<?, ?> map && map.isEmpty())
                || (value instanceof Collection<?> collection && collection.isEmpty());
    }

    private String text(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value);
        return StringUtils.hasText(text) ? text.trim() : null;
    }

    private String firstText(String... values) {
        for (String value : values) {
            if (StringUtils.hasText(value)) {
                return value.trim();
            }
        }
        return null;
    }
}
