package com.enterprise.ai.runtime.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuntimeEvalDeterministicJudgeTest {

    private final RuntimeEvalJsonSupport json = new RuntimeEvalJsonSupport(new ObjectMapper());
    private final RuntimeEvalDeterministicJudge judge = new RuntimeEvalDeterministicJudge(json);

    @Test
    void treatsExpectedSafetyBlockAsAValidNegativeRuntimeResult() {
        Map<String, Object> response = Map.of(
                "success", false,
                "answer", "blocked",
                "metadata", Map.of("code", "EVAL_SIDE_EFFECT_BLOCKED"),
                "steps", List.of());
        Map<String, Object> expected = Map.of(
                "success", false,
                "code", "EVAL_SIDE_EFFECT_BLOCKED");

        RuntimeEvalDeterministicJudge.Judgement result = judge.evaluate(
                response, expected, defaultSuite(), 25);

        assertTrue(result.passed());
        assertEquals(1.0, result.score());
        assertTrue(result.scores().stream().allMatch(RuntimeEvalDeterministicJudge.ScoreResult::passed));
    }

    @Test
    void requiredLatencyBudgetFailsTheAggregateGate() {
        Map<String, Object> suite = Map.of(
                "passThreshold", 0.5,
                "evaluators", List.of(
                        Map.of("key", "runtime", "type", "RUNTIME_SUCCESS", "weight", 1, "required", true),
                        Map.of("key", "latency", "type", "LATENCY_BUDGET", "weight", 1,
                                "required", true, "maxLatencyMs", 100)));

        RuntimeEvalDeterministicJudge.Judgement result = judge.evaluate(
                Map.of("success", true, "metadata", Map.of(), "steps", List.of()),
                Map.of("success", true), suite, 250);

        assertFalse(result.passed());
        assertEquals(2, result.scores().size());
        assertFalse(result.scores().get(1).passed());
    }

    private Map<String, Object> defaultSuite() {
        return Map.of(
                "passThreshold", 0.8,
                "evaluators", List.of(
                        Map.of("key", "runtime_success", "type", "RUNTIME_SUCCESS", "weight", 0.4,
                                "required", true),
                        Map.of("key", "assertions", "type", "DETERMINISTIC_ASSERTIONS", "weight", 0.6,
                                "required", true)));
    }
}
