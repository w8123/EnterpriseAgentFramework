package com.enterprise.ai.runtime.workflow;

import com.enterprise.ai.agent.graph.GraphSpec;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Read-only configuration and route helpers shared by release validators. */
final class RuntimeWorkflowValidationSupport {

    private RuntimeWorkflowValidationSupport() {
    }

    static boolean hasRouteEdge(List<GraphSpec.Edge> edges, String nodeId, String route) {
        return edges.stream()
                .filter(edge -> edge != null && nodeId.equals(text(edge.getFrom())))
                .map(GraphSpec.Edge::getCondition)
                .map(RuntimeWorkflowValidationSupport::routeCondition)
                .anyMatch(condition -> routesEquivalent(route, condition));
    }

    static boolean routesEquivalent(String route, String condition) {
        if (route.equalsIgnoreCase(condition)) {
            return true;
        }
        boolean routeIsDefault = "else".equalsIgnoreCase(route) || "default".equalsIgnoreCase(route);
        boolean conditionIsDefault = "else".equalsIgnoreCase(condition) || "default".equalsIgnoreCase(condition);
        return routeIsDefault && conditionIsDefault;
    }

    static String routeCondition(String rawCondition) {
        String condition = text(rawCondition);
        if (condition.regionMatches(true, 0, "route:", 0, "route:".length())) {
            return condition.substring("route:".length()).trim();
        }
        return condition;
    }

    static Map<String, Object> mapValue(Object value) {
        if (!(value instanceof Map<?, ?> map)) {
            return Map.of();
        }
        Map<String, Object> result = new LinkedHashMap<>();
        map.forEach((key, item) -> result.put(String.valueOf(key), item));
        return result;
    }

    static boolean hasUnconditionalEdge(List<GraphSpec.Edge> edges, String nodeId) {
        return edges.stream()
                .filter(edge -> edge != null && nodeId.equals(text(edge.getFrom())))
                .map(GraphSpec.Edge::getCondition)
                .map(RuntimeWorkflowValidationSupport::routeCondition)
                .anyMatch(condition -> !StringUtils.hasText(condition)
                        || "always".equalsIgnoreCase(condition)
                        || "success".equalsIgnoreCase(condition));
    }

    static Object firstPresent(Object first, Object... rest) {
        if (first != null) {
            return first;
        }
        if (rest != null) {
            for (Object item : rest) {
                if (item != null) {
                    return item;
                }
            }
        }
        return null;
    }

    static int intValue(Object value, int fallback) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value != null) {
            try {
                return Integer.parseInt(String.valueOf(value).trim());
            } catch (NumberFormatException ignored) {
            }
        }
        return fallback;
    }

    static double doubleValue(Object value, double fallback) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value != null) {
            try {
                return Double.parseDouble(String.valueOf(value).trim());
            } catch (NumberFormatException ignored) {
            }
        }
        return fallback;
    }

    static String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    static String firstText(String... values) {
        for (String value : values) {
            if (StringUtils.hasText(value)) {
                return value;
            }
        }
        return null;
    }

    /** Reads a legacy nested node config, applying top-level editor overrides. */
    static Map<String, Object> effectiveNodeConfig(GraphSpec.Node node, String nestedKey) {
        Map<String, Object> config = node == null || node.getConfig() == null ? Map.of() : node.getConfig();
        Map<String, Object> nested = mapValue(config.get(nestedKey));
        if (nested.isEmpty()) return config;
        Map<String, Object> merged = new LinkedHashMap<>(nested);
        config.forEach((key, value) -> {
            if (!nestedKey.equals(key)) merged.put(key, value);
        });
        return merged;
    }
}
