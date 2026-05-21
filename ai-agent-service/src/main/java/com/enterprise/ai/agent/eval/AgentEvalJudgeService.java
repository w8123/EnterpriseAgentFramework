package com.enterprise.ai.agent.eval;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

@Service
public class AgentEvalJudgeService {

    private final ObjectMapper objectMapper;

    public AgentEvalJudgeService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper == null ? new ObjectMapper() : objectMapper;
    }

    public JudgeResult judge(String answer,
                             Map<String, Object> context,
                             String expectedJson,
                             String judgeConfigJson) {
        Map<String, Object> expected = readMap(expectedJson);
        Map<String, Object> judgeConfig = readMap(judgeConfigJson);
        List<String> failures = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        int assertionCount = 0;

        assertionCount += checkContains(answer, expected.get("contains"), failures);
        assertionCount += checkRegex(answer, expected.get("regex"), failures);
        assertionCount += checkJsonPath(context == null ? Map.of() : context, expected.get("jsonPath"), failures);

        Double semanticScore = semanticScore(judgeConfig);
        Object minSemanticScore = expected.get("minSemanticScore");
        if (minSemanticScore instanceof Number min && semanticScore != null && semanticScore < min.doubleValue()) {
            failures.add("semantic score " + semanticScore + " below minSemanticScore " + min.doubleValue());
        }

        if (assertionCount == 0 && minSemanticScore == null) {
            boolean passed = answer != null && !answer.isBlank();
            return new JudgeResult(passed, passed ? 0.6 : 0.0, semanticScore, failures, warnings,
                    Map.of("mode", "weak-answer-presence"));
        }

        boolean passed = failures.isEmpty();
        double score = passed ? 1.0 : 0.0;
        if (passed && semanticScore != null) {
            score = Math.max(score, semanticScore);
        }
        return new JudgeResult(passed, score, semanticScore, failures, warnings,
                Map.of("assertionCount", assertionCount));
    }

    private int checkContains(String answer, Object raw, List<String> failures) {
        List<String> values = stringList(raw);
        for (String value : values) {
            if (answer == null || !answer.contains(value)) {
                failures.add("contains assertion failed: " + value);
            }
        }
        return values.size();
    }

    private int checkRegex(String answer, Object raw, List<String> failures) {
        List<String> values = stringList(raw);
        for (String regex : values) {
            if (answer == null || !Pattern.compile(regex, Pattern.DOTALL).matcher(answer).find()) {
                failures.add("regex assertion failed: " + regex);
            }
        }
        return values.size();
    }

    private int checkJsonPath(Map<String, Object> context, Object raw, List<String> failures) {
        if (!(raw instanceof Map<?, ?> map)) {
            return 0;
        }
        int count = 0;
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            count++;
            String path = String.valueOf(entry.getKey());
            Object actual = resolvePath(context, path);
            Object expected = entry.getValue();
            if (!Objects.equals(stringify(expected), stringify(actual))) {
                failures.add("jsonPath assertion failed: " + path
                        + ", expected=" + stringify(expected)
                        + ", actual=" + stringify(actual));
            }
        }
        return count;
    }

    private Object resolvePath(Object root, String path) {
        Object current = root;
        for (String part : path.split("\\.")) {
            if (current instanceof Map<?, ?> map) {
                current = map.get(part);
                continue;
            }
            if (current instanceof List<?> list) {
                try {
                    int index = Integer.parseInt(part);
                    current = index >= 0 && index < list.size() ? list.get(index) : null;
                } catch (NumberFormatException ignored) {
                    current = null;
                }
                continue;
            }
            return null;
        }
        return current;
    }

    private Map<String, Object> readMap(String json) {
        if (json == null || json.isBlank()) {
            return new LinkedHashMap<>();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (Exception ex) {
            return new LinkedHashMap<>();
        }
    }

    private List<String> stringList(Object raw) {
        if (!(raw instanceof List<?> list)) {
            return List.of();
        }
        return list.stream()
                .map(this::stringify)
                .filter(value -> !value.isBlank())
                .toList();
    }

    private Double semanticScore(Map<String, Object> judgeConfig) {
        Object value = judgeConfig.get("semanticScore");
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        return null;
    }

    private String stringify(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    public record JudgeResult(
            boolean passed,
            double score,
            Double semanticScore,
            List<String> failures,
            List<String> warnings,
            Map<String, Object> details
    ) {}
}
