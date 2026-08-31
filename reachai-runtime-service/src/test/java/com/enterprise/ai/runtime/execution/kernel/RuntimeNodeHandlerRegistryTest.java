package com.enterprise.ai.runtime.execution.kernel;

import com.enterprise.ai.runtime.execution.RuntimeGraphSpecExecutionResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RuntimeNodeHandlerRegistryTest {

    @Test
    void normalizesAndExposesExactExpectedTypes() {
        RuntimeNodeHandlerRegistry registry = RuntimeNodeHandlerRegistry.builder()
                .register("answer", (node, context) -> success())
                .build(Set.of("ANSWER"));

        assertEquals(Set.of("ANSWER"), registry.handledNodeTypes());
        assertEquals("ANSWER", registry.find("answer").orElseThrow().nodeType());
    }

    @Test
    void rejectsMissingOrDuplicateHandlers() {
        RuntimeNodeHandlerRegistry.Builder builder = RuntimeNodeHandlerRegistry.builder()
                .register("ANSWER", (node, context) -> success());

        assertThrows(IllegalStateException.class, () -> builder.build(Set.of("ANSWER", "TOOL")));
        assertThrows(IllegalStateException.class, () -> builder.register(
                "answer", (node, context) -> success()));
    }

    private static RuntimeGraphSpecExecutionResult success() {
        return new RuntimeGraphSpecExecutionResult(
                true, "OK", "ok", "n1", "ANSWER", List.of(), Map.of(), Map.of());
    }
}
