package com.enterprise.ai.agent.runtime.host.slot;

import com.enterprise.ai.agent.client.ModelServiceClient;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class SlotFillingService {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private final ModelServiceClient modelServiceClient;
    private final ObjectMapper objectMapper;

    public SlotFillingService(ModelServiceClient modelServiceClient, ObjectMapper objectMapper) {
        this.modelServiceClient = modelServiceClient;
        this.objectMapper = objectMapper == null ? new ObjectMapper() : objectMapper;
    }

    public SlotFillingOutcome fill(SlotFillingRequest request) {
        Map<String, Object> values = new LinkedHashMap<>();
        Map<String, Object> prefilled = new LinkedHashMap<>();
        Map<String, SlotResult> slots = new LinkedHashMap<>();
        List<String> missing = new ArrayList<>();
        List<String> confirmationRequired = new ArrayList<>();

        for (Map<String, Object> field : request.fields()) {
            String key = fieldName(field);
            if (key.isBlank()) {
                continue;
            }
            String targetPath = firstNonBlank(asString(field.get("targetPath")), asString(field.get("target")), key);
            Candidate candidate = userInputCandidate(request.incoming(), field, key, targetPath);
            if (candidate == null && slotFillingEnabled(field)) {
                candidate = strategyCandidate(request, field, key, targetPath);
            }

            if (candidate != null && !isBlank(candidate.value())) {
                boolean confirm = confirmationRequired(field, candidate);
                slots.put(key, new SlotResult(key, targetPath, candidate.value(), candidate.strategy(), candidate.source(), candidate.confidence(), confirm));
                if (confirm) {
                    prefilled.put(key, candidate.value());
                    confirmationRequired.add(key);
                } else {
                    values.put(key, candidate.value());
                    prefilled.put(key, candidate.value());
                }
            } else if (Boolean.TRUE.equals(field.get("required"))) {
                missing.add(key);
            }
        }

        return new SlotFillingOutcome(values, prefilled, slots, missing, confirmationRequired);
    }

    private Candidate strategyCandidate(SlotFillingRequest request, Map<String, Object> field, String key, String targetPath) {
        for (String strategy : strategies(field)) {
            String normalized = strategy.toUpperCase();
            Candidate candidate = switch (normalized) {
                case "RULE" -> ruleCandidate(request.message(), field);
                case "DICTIONARY" -> dictionaryCandidate(request.message(), field);
                case "LLM" -> llmCandidate(request, field, key, targetPath);
                default -> null;
            };
            if (candidate != null && !isBlank(candidate.value())) {
                return candidate;
            }
        }
        return null;
    }

    private Candidate userInputCandidate(Map<String, Object> incoming, Map<String, Object> field, String key, String targetPath) {
        Object value = incoming.get(key);
        if (isBlank(value) && !targetPath.equals(key)) {
            value = incoming.get(targetPath);
        }
        if (isBlank(value) && targetPath.contains(".")) {
            value = incoming.get(targetPath.replace(".", "_"));
        }
        if (isBlank(value) && targetPath.contains(".")) {
            value = traverse(incoming, targetPath.split("\\."), 0, null);
        }
        if (isBlank(value) && field.containsKey("defaultValue")) {
            value = field.get("defaultValue");
        }
        return isBlank(value) ? null : new Candidate(value, "USER_INPUT", "explicit", 1.0);
    }

    private Candidate ruleCandidate(String message, Map<String, Object> field) {
        List<String> patterns = stringList(asMap(slotConfig(field).get("rule")).get("patterns"));
        if (patterns.isEmpty()) {
            patterns = stringList(slotConfig(field).get("patterns"));
        }
        for (String pattern : patterns) {
            Matcher matcher = Pattern.compile(pattern).matcher(message == null ? "" : message);
            if (matcher.find()) {
                String value = matcher.groupCount() >= 1 ? matcher.group(1) : matcher.group();
                return new Candidate(clean(value), "RULE", pattern, doubleValue(slotConfig(field).get("ruleConfidence"), 0.75));
            }
        }
        return null;
    }

    private Candidate dictionaryCandidate(String message, Map<String, Object> field) {
        List<String> entries = stringList(slotConfig(field).get("dictionaryValues"));
        if (entries.isEmpty()) {
            entries = stringList(asMap(slotConfig(field).get("dictionary")).get("values"));
        }
        String text = message == null ? "" : message;
        for (String entry : entries) {
            if (!entry.isBlank() && text.contains(entry)) {
                return new Candidate(entry, "DICTIONARY", "inline", doubleValue(slotConfig(field).get("dictionaryConfidence"), 0.9));
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private Candidate llmCandidate(SlotFillingRequest request, Map<String, Object> field, String key, String targetPath) {
        if (modelServiceClient == null || request.modelInstanceId().isBlank()) {
            return null;
        }
        String label = firstNonBlank(asString(field.get("label")), asString(field.get("description")), key);
        String prompt = firstNonBlank(asString(slotConfig(field).get("llmPrompt")),
                "从用户输入中抽取字段值。只返回 JSON，不要解释。格式：{\"values\":{\"" + key
                        + "\":\"值或空字符串\"},\"confidence\":{\"" + key + "\":0到1之间的小数}}。");
        ModelServiceClient.ModelChatResult result = modelServiceClient.chat(ModelServiceClient.ModelChatRequest.builder()
                .modelInstanceId(firstNonBlank(asString(slotConfig(field).get("modelInstanceId")), request.modelInstanceId()))
                .messages(List.of(
                        ModelServiceClient.ModelChatRequest.ChatMessage.builder()
                                .role("system")
                                .content(prompt)
                                .build(),
                        ModelServiceClient.ModelChatRequest.ChatMessage.builder()
                                .role("user")
                                .content("用户输入：" + nullToEmpty(request.message())
                                        + "\n字段 key：" + key
                                        + "\n字段标签：" + label
                                        + "\n目标路径：" + targetPath)
                                .build()))
                .build());
        if (result == null || result.getCode() != 200 || result.getData() == null) {
            return null;
        }
        Map<String, Object> parsed = parseJsonObject(result.getData().getContent());
        Map<String, Object> values = asMap(parsed.get("values"));
        Map<String, Object> confidence = asMap(parsed.get("confidence"));
        Object value = values.containsKey(key) ? values.get(key) : parsed.get(key);
        double score = doubleValue(confidence.get(key), doubleValue(parsed.get("confidence"), 0.7));
        return isBlank(value) ? null : new Candidate(value, "LLM", "model", score);
    }

    private boolean confirmationRequired(Map<String, Object> field, Candidate candidate) {
        String policy = firstNonBlank(asString(slotConfig(field).get("confirmPolicy")), "LOW_CONFIDENCE").toUpperCase();
        if ("ALWAYS".equals(policy)) {
            return true;
        }
        if ("NEVER".equals(policy)) {
            return false;
        }
        double threshold = doubleValue(slotConfig(field).get("confidenceThreshold"), 0.85);
        return candidate.confidence() < threshold;
    }

    private boolean slotFillingEnabled(Map<String, Object> field) {
        return Boolean.TRUE.equals(slotConfig(field).get("enabled"));
    }

    private List<String> strategies(Map<String, Object> field) {
        List<String> configured = stringList(slotConfig(field).get("strategies"));
        if (configured.isEmpty()) {
            return List.of("LLM");
        }
        List<String> strategies = configured.stream()
                .filter(strategy -> !"USER_INPUT".equalsIgnoreCase(strategy))
                .toList();
        return strategies.isEmpty() ? List.of("LLM") : strategies;
    }

    private Map<String, Object> slotConfig(Map<String, Object> field) {
        return asMap(field.get("slotFilling"));
    }

    private Map<String, Object> parseJsonObject(String content) {
        String text = stripJsonFence(content);
        try {
            return objectMapper.readValue(text, MAP_TYPE);
        } catch (Exception ignored) {
            return Map.of();
        }
    }

    private String stripJsonFence(String content) {
        String text = content == null ? "" : content.trim();
        if (text.startsWith("```")) {
            text = text.replaceFirst("^```(?:json)?\\s*", "");
            text = text.replaceFirst("\\s*```$", "");
        }
        return text.trim();
    }

    private String clean(String value) {
        String text = value == null ? "" : value.trim();
        text = text.replaceAll("^[，。,.\\s]+|[，。,.\\s]+$", "");
        return text;
    }

    private Object traverse(Object value, String[] parts, int start, Object fallback) {
        Object current = value;
        for (int i = start; i < parts.length; i++) {
            if (current == null) {
                return fallback;
            }
            if (current instanceof Map<?, ?> map) {
                current = map.get(parts[i]);
            } else {
                return fallback;
            }
        }
        return current == null ? fallback : current;
    }

    private String fieldName(Map<String, Object> field) {
        return firstNonBlank(asString(field.get("key")), asString(field.get("name")));
    }

    private Map<String, Object> asMap(Object value) {
        if (value instanceof Map<?, ?> raw) {
            Map<String, Object> map = new LinkedHashMap<>();
            raw.forEach((key, item) -> map.put(String.valueOf(key), item));
            return map;
        }
        return Map.of();
    }

    private List<String> stringList(Object value) {
        if (value instanceof List<?> list) {
            return list.stream().map(this::asString).filter(item -> !item.isBlank()).toList();
        }
        return List.of();
    }

    private String asString(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private boolean isBlank(Object value) {
        return value == null || String.valueOf(value).isBlank();
    }

    private double doubleValue(Object value, double fallback) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        try {
            return Double.parseDouble(asString(value));
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private record Candidate(Object value, String strategy, String source, double confidence) {}

    public record SlotFillingRequest(String message,
                                     Map<String, Object> incoming,
                                     List<Map<String, Object>> fields,
                                     String modelInstanceId) {}

    public record SlotResult(String key,
                             String targetPath,
                             Object value,
                             String strategy,
                             String source,
                             double confidence,
                             boolean confirmationRequired) {
        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("key", key);
            map.put("targetPath", targetPath);
            map.put("value", value);
            map.put("strategy", strategy);
            map.put("source", source);
            map.put("confidence", confidence);
            map.put("confirmationRequired", confirmationRequired);
            return map;
        }
    }

    public record SlotFillingOutcome(Map<String, Object> values,
                                     Map<String, Object> prefilled,
                                     Map<String, SlotResult> slots,
                                     List<String> missing,
                                     List<String> confirmationRequired) {}
}
