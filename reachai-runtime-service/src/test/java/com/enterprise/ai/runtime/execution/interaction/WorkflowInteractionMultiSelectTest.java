package com.enterprise.ai.runtime.execution.interaction;

import com.enterprise.ai.agent.graph.GraphSpec;
import com.enterprise.ai.runtime.execution.RuntimeGraphSpecExecutionResult;
import org.junit.jupiter.api.Test;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class WorkflowInteractionMultiSelectTest {
    private static final List<Map<String, Object>> OPTIONS = List.of(Map.of("value", "a", "label", "甲"), Map.of("value", "b", "label", "乙"));

    @Test
    void choiceAcceptsTheArraySubmittedByTheSharedMultiSelectRenderer() {
        var result = submit(choice(), Map.of("value", List.of("a", "b")));
        assertTrue(result.success(), "Declared multi-select values must resume: " + result.code());
        assertEquals(List.of("a", "b"), ((Map<?, ?>) result.metadata().get("structuredOutput")).get("selected"));
    }

    @Test
    void collectAcceptsDeclaredMultiSelectFieldValues() {
        var result = submit(form(), Map.of("items", List.of("a", "b")));
        assertTrue(result.success(), "Declared multi-select form values must resume: " + result.code());
    }

    @Test
    void choiceRejectsAnArrayContainingAnUndeclaredValue() {
        assertTrue(submit(choice(), Map.of("value", List.of("a", "unknown"))).isWaitingUser());
    }

    @Test
    void collectRejectsAnArrayContainingAnUndeclaredValue() {
        assertTrue(submit(form(), Map.of("items", List.of("a", "unknown"))).isWaitingUser());
    }

    @Test
    void multiSelectChoiceRejectsAScalarDespiteItsMembership() {
        assertTrue(submit(choice(), Map.of("value", "a")).isWaitingUser());
    }

    @Test
    void multiSelectFormRejectsAScalarDespiteItsMembership() {
        assertTrue(submit(form(), Map.of("items", "a")).isWaitingUser());
    }

    private GraphSpec.Node choice() {
        return GraphSpec.Node.builder().id("choose").type("INTERACTION").config(Map.of(
                "interactionType", "USER_CHOICE", "component", "multi_select", "options", OPTIONS)).build();
    }

    private GraphSpec.Node form() {
        return GraphSpec.Node.builder().id("form").type("INTERACTION").config(Map.of(
                "interactionType", "COLLECT_INPUT", "fields", List.of(Map.of(
                        "key", "items", "type", "multi_select", "required", true, "options", OPTIONS)))).build();
    }

    private RuntimeGraphSpecExecutionResult submit(GraphSpec.Node node, Map<String, Object> values) {
        Map<String, Object> context = new LinkedHashMap<>();
        var waiting = WorkflowInteractionNodeHandler.execute(node, context);
        assertTrue(waiting.isWaitingUser());
        context.put(WorkflowInteractionCodes.RESUME_CONTEXT_KEY, Map.of("interactionId", waiting.interactionId(),
                "nodeId", node.getId(), "action", "choose", "values", values));
        return WorkflowInteractionNodeHandler.execute(node, context);
    }
}
