package com.enterprise.ai.runtime.eval;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

@Component
@RequiredArgsConstructor
class RuntimeEvalDeterministicJudge {

    private final RuntimeEvalJsonSupport json;

    Judgement evaluate(Map<String, Object> response,
                       Map<String, Object> expected,
                       Map<String, Object> suiteConfig,
                       int elapsedMs) {
        boolean runtimeSuccess = Boolean.TRUE.equals(response.get("success"));
        List<ScoreResult> scores = new ArrayList<>();
        List<Map<String, Object>> evaluators = json.mapList(suiteConfig.get("evaluators"));
        for (Map<String, Object> evaluator : evaluators) {
            String key = requiredText(evaluator, "key");
            String type = requiredText(evaluator, "type").toUpperCase(Locale.ROOT);
            double weight = doubleValue(evaluator.get("weight"), 1.0);
            boolean required = booleanValue(evaluator.get("required"), false);
            ScoreResult score = switch (type) {
                case "RUNTIME_SUCCESS" -> {
                    boolean expectedSuccess = booleanValue(expected.get("success"), true);
                    boolean matched = runtimeSuccess == expectedSuccess;
                    yield new ScoreResult(
                            key, type, matched ? 1.0 : 0.0, matched, weight, required,
                            matched ? "Runtime result matched expected success state"
                                    : "Runtime success state did not match expectation",
                            Map.of("expectedSuccess", expectedSuccess, "runtimeSuccess", runtimeSuccess));
                }
                case "DETERMINISTIC_ASSERTIONS" -> assertionScore(
                        key, type, weight, required, response, expected, runtimeSuccess);
                case "LATENCY_BUDGET" -> latencyScore(key, type, weight, required, evaluator, expected, elapsedMs);
                default -> throw new IllegalArgumentException("Unsupported Eval evaluator type: " + type);
            };
            scores.add(score);
        }
        double totalWeight = scores.stream().mapToDouble(ScoreResult::weight).sum();
        double weighted = scores.stream().mapToDouble(value -> value.score() * value.weight()).sum();
        double aggregate = totalWeight <= 0
                ? scores.stream().mapToDouble(ScoreResult::score).average().orElse(0)
                : weighted / totalWeight;
        double threshold = doubleValue(suiteConfig.get("passThreshold"), 0.8);
        boolean requiredPassed = scores.stream().filter(ScoreResult::required).allMatch(ScoreResult::passed);
        return new Judgement(aggregate, aggregate >= threshold && requiredPassed, List.copyOf(scores));
    }

    private ScoreResult assertionScore(String key,
                                       String type,
                                       double weight,
                                       boolean required,
                                       Map<String, Object> response,
                                       Map<String, Object> expected,
                                       boolean runtimeSuccess) {
        List<Map<String, Object>> checks = new ArrayList<>();
        check(checks, "success", booleanValue(expected.get("success"), true), runtimeSuccess);
        String answer = firstText(text(response, "answer"), "");
        if (expected.containsKey("answerEquals")) {
            check(checks, "answerEquals", text(expected, "answerEquals"), answer);
        }
        for (String fragment : stringList(expected.get("answerContains"))) {
            check(checks, "answerContains:" + fragment, true, answer.contains(fragment));
        }
        for (String fragment : stringList(expected.get("answerNotContains"))) {
            check(checks, "answerNotContains:" + fragment, true, !answer.contains(fragment));
        }

        Map<String, Object> metadata = json.map(response.get("metadata"));
        minimum(checks, expected, metadata, "minWorkflowCalls", "workflowCallCount");
        maximum(checks, expected, metadata, "maxWorkflowCalls", "workflowCallCount");
        minimum(checks, expected, metadata, "minPlanCount", "planCount");
        maximum(checks, expected, metadata, "maxPlanCount", "planCount");
        minimum(checks, expected, metadata, "minReplanCount", "replanCount");
        maximum(checks, expected, metadata, "maxReplanCount", "replanCount");
        if (expected.containsKey("code")) {
            check(checks, "code", text(expected, "code"), text(metadata, "code"));
        }
        if (expected.containsKey("interactionPending")) {
            check(checks, "interactionPending", booleanValue(expected.get("interactionPending"), false),
                    Boolean.TRUE.equals(metadata.get("interactionPending")));
        }
        json.map(expected.get("metadataEquals")).forEach((name, value) ->
                check(checks, "metadata." + name, value, metadata.get(name)));

        List<Map<String, Object>> steps = json.mapList(response.get("steps"));
        List<String> calledTools = steps.stream()
                .filter(step -> "workflow".equalsIgnoreCase(text(step, "name")))
                .map(step -> text(json.map(step.get("detail")), "toolName"))
                .filter(StringUtils::hasText)
                .toList();
        for (String tool : stringList(expected.get("calledTools"))) {
            check(checks, "calledTool:" + tool, true, calledTools.contains(tool));
        }
        for (String tool : stringList(expected.get("forbiddenTools"))) {
            check(checks, "forbiddenTool:" + tool, true, !calledTools.contains(tool));
        }
        if (expected.containsKey("policyDecision")) {
            String decision = text(expected, "policyDecision");
            boolean found = steps.stream()
                    .filter(step -> "policy".equalsIgnoreCase(text(step, "name")))
                    .map(step -> text(json.map(step.get("detail")), "decision"))
                    .anyMatch(value -> decision != null && decision.equalsIgnoreCase(value));
            check(checks, "policyDecision", true, found);
        }

        int passed = (int) checks.stream().filter(value -> Boolean.TRUE.equals(value.get("passed"))).count();
        double score = checks.isEmpty() ? (runtimeSuccess ? 1 : 0) : (double) passed / checks.size();
        boolean allPassed = passed == checks.size();
        String reason = allPassed ? "All deterministic assertions passed" : checks.stream()
                .filter(value -> !Boolean.TRUE.equals(value.get("passed")))
                .map(value -> String.valueOf(value.get("name")))
                .reduce((left, right) -> left + ", " + right)
                .map(value -> "Failed assertions: " + value)
                .orElse("Deterministic assertions failed");
        return new ScoreResult(key, type, score, allPassed, weight, required, reason,
                Map.of("checks", List.copyOf(checks), "passedCount", passed, "checkCount", checks.size()));
    }

    private ScoreResult latencyScore(String key,
                                     String type,
                                     double weight,
                                     boolean required,
                                     Map<String, Object> evaluator,
                                     Map<String, Object> expected,
                                     int elapsedMs) {
        int maximum = intValue(expected.get("maxLatencyMs"), intValue(evaluator.get("maxLatencyMs"), 5_000));
        boolean passed = elapsedMs <= maximum;
        double score = passed ? 1.0 : Math.max(0.0, Math.min(1.0, (double) maximum / Math.max(1, elapsedMs)));
        return new ScoreResult(key, type, score, passed, weight, required,
                passed ? "Latency budget met" : "Latency exceeded " + maximum + " ms",
                Map.of("elapsedMs", elapsedMs, "maxLatencyMs", maximum));
    }

    private void minimum(List<Map<String, Object>> checks,
                         Map<String, Object> expected,
                         Map<String, Object> actual,
                         String expectedKey,
                         String actualKey) {
        if (expected.containsKey(expectedKey)) {
            check(checks, expectedKey, true,
                    intValue(actual.get(actualKey), 0) >= intValue(expected.get(expectedKey), 0));
        }
    }

    private void maximum(List<Map<String, Object>> checks,
                         Map<String, Object> expected,
                         Map<String, Object> actual,
                         String expectedKey,
                         String actualKey) {
        if (expected.containsKey(expectedKey)) {
            check(checks, expectedKey, true,
                    intValue(actual.get(actualKey), 0) <= intValue(expected.get(expectedKey), 0));
        }
    }

    private void check(List<Map<String, Object>> checks, String name, Object expected, Object actual) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("name", name);
        detail.put("expected", expected);
        detail.put("actual", actual);
        detail.put("passed", Objects.equals(expected, actual));
        checks.add(detail);
    }

    private List<String> stringList(Object value) {
        if (value instanceof List<?> list) return list.stream().map(String::valueOf).toList();
        if (value instanceof String text && StringUtils.hasText(text)) return List.of(text);
        return List.of();
    }

    private String requiredText(Map<String, Object> values, String field) {
        String value = text(values, field);
        if (!StringUtils.hasText(value)) throw new IllegalArgumentException("Eval evaluator " + field + " is required");
        return value;
    }

    private String text(Map<String, Object> values, String field) {
        Object value = values == null ? null : values.get(field);
        return value == null ? null : String.valueOf(value).trim();
    }

    private int intValue(Object value, int fallback) {
        if (value instanceof Number number) return number.intValue();
        if (value instanceof String text && StringUtils.hasText(text)) return Integer.parseInt(text);
        return fallback;
    }

    private double doubleValue(Object value, double fallback) {
        if (value instanceof Number number) return number.doubleValue();
        if (value instanceof String text && StringUtils.hasText(text)) return Double.parseDouble(text);
        return fallback;
    }

    private boolean booleanValue(Object value, boolean fallback) {
        if (value instanceof Boolean bool) return bool;
        if (value instanceof Number number) return number.intValue() != 0;
        if (value instanceof String text && StringUtils.hasText(text)) return Boolean.parseBoolean(text);
        return fallback;
    }

    private String firstText(String... values) {
        for (String value : values) if (StringUtils.hasText(value)) return value.trim();
        return null;
    }

    record ScoreResult(String key,
                       String type,
                       double score,
                       boolean passed,
                       double weight,
                       boolean required,
                       String reason,
                       Map<String, Object> metadata) {
    }

    record Judgement(double score, boolean passed, List<ScoreResult> scores) {
    }
}
