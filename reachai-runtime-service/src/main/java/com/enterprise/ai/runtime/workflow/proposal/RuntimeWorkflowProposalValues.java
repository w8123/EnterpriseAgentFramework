package com.enterprise.ai.runtime.workflow.proposal;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Typed value reading and defensive JSON map copies for proposal normalization. */
final class RuntimeWorkflowProposalValues {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final ObjectMapper objectMapper;

    RuntimeWorkflowProposalValues(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    static String compactToken(String value) {
        return slug(value).replace("_", "").toLowerCase(Locale.ROOT);
    }

    List<Map<String, Object>> fields(Map<String, Object> config) {
        Object raw = config.get("fields");
        if (!(raw instanceof List<?> list)) return List.of();
        return list.stream()
                .filter(Map.class::isInstance)
                .map(this::mutableMap)
                .toList();
    }

    List<Map<String, Object>> classes(Map<String, Object> config) {
        Object raw = config.get("classes");
        if (!(raw instanceof List<?> list)) return List.of();
        return list.stream()
                .filter(Map.class::isInstance)
                .map(this::mutableMap)
                .toList();
    }

    static List<String> arrayValue(Object value) {
        if (!(value instanceof List<?> list)) return List.of();
        return list.stream().map(String::valueOf).filter(StringUtils::hasText).toList();
    }

    static List<String> arrayOrDefault(Object value, List<String> fallback) {
        List<String> items = arrayValue(value);
        return items.isEmpty() ? fallback : items;
    }

    Map<String, Object> mutableMap(Object value) {
        if (value == null) return new LinkedHashMap<>();
        if (value instanceof Map<?, ?> map) return objectMapper.convertValue(map, MAP_TYPE);
        return objectMapper.convertValue(value, MAP_TYPE);
    }

    static String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    static String firstText(String... values) {
        for (String value : values) {
            if (StringUtils.hasText(value)) return value.trim();
        }
        return "";
    }

    static boolean bool(Object value) {
        if (value instanceof Boolean b) return b;
        return "true".equalsIgnoreCase(text(value));
    }

    static boolean boolOr(Object value, boolean fallback) {
        if (value == null) return fallback;
        return bool(value);
    }

    static int integer(Object value, int fallback) {
        if (value instanceof Number number) return number.intValue();
        try {
            return StringUtils.hasText(text(value)) ? Integer.parseInt(text(value)) : fallback;
        } catch (Exception ex) {
            return fallback;
        }
    }

    static Long longValue(Object value) {
        if (value instanceof Number number) return number.longValue();
        try {
            return StringUtils.hasText(text(value)) ? Long.parseLong(text(value)) : null;
        } catch (Exception ex) {
            return null;
        }
    }

    static Double doubleValue(Object value) {
        if (value instanceof Number number) return number.doubleValue();
        try {
            return StringUtils.hasText(text(value)) ? Double.parseDouble(text(value)) : null;
        } catch (Exception ex) {
            return null;
        }
    }

    static double doubleOr(Object value, double fallback) {
        Double number = doubleValue(value);
        return number == null ? fallback : number;
    }

    static String blankToNull(String value) {
        return StringUtils.hasText(value) ? value : null;
    }

    static String slug(String value) {
        String raw = firstText(value, "node").trim();
        String normalized = raw.replaceAll("[^\\p{IsAlphabetic}\\p{IsDigit}_-]+", "_")
                .replace('-', '_')
                .replaceAll("_+", "_")
                .replaceAll("^_|_$", "")
                .toLowerCase(Locale.ROOT);
        return StringUtils.hasText(normalized) ? normalized : "node";
    }

    static String key(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
