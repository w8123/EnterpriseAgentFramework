package com.enterprise.ai.agent.graph;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Shared, immutable interpretation of current and legacy GraphSpec LOOP configuration. */
public record GraphSpecLoopConfig(
        String mode,
        String collection,
        String itemAlias,
        String indexAlias,
        String outputAlias,
        String bodyOutput,
        String bodyEntry,
        String bodyExit,
        Set<String> bodyNodeIds,
        Integer maxIterations) {

    public static final int DEFAULT_MAX_ITERATIONS = 100;
    public static final int HARD_MAX_ITERATIONS = 1000;

    public GraphSpecLoopConfig {
        bodyNodeIds = Collections.unmodifiableSet(new LinkedHashSet<>(bodyNodeIds));
    }

    public static GraphSpecLoopConfig from(GraphSpec.Node node) {
        Map<String, Object> config = effectiveConfig(node == null ? null : node.getConfig());
        String bodyEntry = text(config.get("bodyEntry"));
        String bodyExit = firstText(text(config.get("bodyExit")), bodyEntry);
        return new GraphSpecLoopConfig(
                firstText(text(config.get("mode")), "FOREACH"),
                firstText(text(config.get("collection")), text(config.get("itemExpression"))),
                firstText(text(config.get("itemAlias")), "item"),
                firstText(text(config.get("indexAlias")), "index"),
                firstText(text(config.get("outputAlias")), text(config.get("loopKey")), "loop_results"),
                firstText(text(config.get("bodyOutput")), "lastOutput"),
                bodyEntry,
                bodyExit,
                bodyNodeIds(config.get("bodyNodeIds"), bodyEntry, bodyExit),
                parseMaxIterations(config.get("maxIterations")));
    }

    /** Missing or blank uses the default; a present invalid limit is rejected by callers. */
    public static Integer parseMaxIterations(Object raw) {
        if (raw == null) return DEFAULT_MAX_ITERATIONS;
        if (raw instanceof String text && !hasText(text)) return DEFAULT_MAX_ITERATIONS;
        int value;
        if (raw instanceof Number number) {
            try {
                value = new BigDecimal(number.toString()).intValueExact();
            } catch (NumberFormatException | ArithmeticException ex) {
                return null;
            }
        } else {
            try {
                value = Integer.parseInt(String.valueOf(raw).trim());
            } catch (NumberFormatException ex) {
                return null;
            }
        }
        return value < 1 || value > HARD_MAX_ITERATIONS ? null : value;
    }

    private static Map<String, Object> effectiveConfig(Map<String, Object> config) {
        if (config == null) return Map.of();
        if (!(config.get("loopConfig") instanceof Map<?, ?> nested) || nested.isEmpty()) return config;
        Map<String, Object> merged = new LinkedHashMap<>();
        nested.forEach((key, value) -> merged.put(String.valueOf(key), value));
        config.forEach((key, value) -> {
            if (!"loopConfig".equals(key)) merged.putIfAbsent(key, value);
        });
        return merged;
    }

    private static Set<String> bodyNodeIds(Object raw, String bodyEntry, String bodyExit) {
        Set<String> ids = new LinkedHashSet<>();
        if (raw instanceof List<?> list) {
            for (Object item : list) {
                if (item != null && hasText(String.valueOf(item))) ids.add(String.valueOf(item).trim());
            }
        }
        if (hasText(bodyEntry)) ids.add(bodyEntry.trim());
        if (hasText(bodyExit)) ids.add(bodyExit.trim());
        return ids;
    }

    private static String text(Object value) {
        if (value == null) return null;
        String text = String.valueOf(value);
        return hasText(text) ? text.trim() : null;
    }

    private static String firstText(String... values) {
        for (String value : values) {
            if (hasText(value)) return value.trim();
        }
        return null;
    }

    private static boolean hasText(String value) {
        if (value == null) return false;
        for (int i = 0; i < value.length(); i++) {
            if (!Character.isWhitespace(value.charAt(i))) return true;
        }
        return false;
    }
}
