package com.enterprise.ai.runtime.execution.kernel;

import com.enterprise.ai.runtime.execution.RuntimeGraphSpecExecutionResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuntimeOutputEmptinessTest {

    @Test
    void pagedBusinessResultsWithoutRowsAreEmpty() {
        assertTrue(empty(Map.of("total", 0, "list", List.of())));
        assertTrue(empty(Map.of("code", 0, "data", Map.of("total", 0, "list", List.of()))));
        assertTrue(empty(Map.of("records", List.of())));
        assertTrue(empty(Map.of()));
        assertTrue(empty(List.of()));
    }

    @Test
    void resultsWithRowsOrScalarsAreNotEmpty() {
        assertFalse(empty(Map.of("total", 2, "list", List.of(Map.of("id", 1)))));
        assertFalse(empty(Map.of("productId", 1)));
        assertFalse(empty(List.of(1)));
    }

    @Test
    void explicitEmptyFlagWins() {
        RuntimeGraphSpecExecutionResult result = new RuntimeGraphSpecExecutionResult(
                true, "OK", "text", "n", "KNOWLEDGE_RETRIEVAL", List.of(), Map.of("empty", true));
        assertTrue(RuntimeOutputEmptiness.isEmpty(result));
    }

    private static boolean empty(Object structured) {
        return RuntimeOutputEmptiness.isEmpty(new RuntimeGraphSpecExecutionResult(
                true, "OK", "x", "n", "TOOL", List.of(), Map.of("structuredOutput", structured)));
    }
}
