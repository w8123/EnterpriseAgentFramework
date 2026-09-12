package com.enterprise.ai.runtime.supervisor;

import com.enterprise.ai.runtime.agent.RuntimeAgentWorkflowToolSnapshot;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class SupervisorWorkflowContinuationTest {
    @Test void rejectsAnOldOrIncompleteCallBudgetSnapshot() {
        var value = continuation(); value.put("continuationSchemaVersion",2);
        assertThrows(IllegalArgumentException.class, () -> SupervisorWorkflowContinuation.parse(value,tools()));
        value.put("continuationSchemaVersion",3); value.remove("executionCallCounts");
        assertThrows(IllegalArgumentException.class, () -> SupervisorWorkflowContinuation.parse(value,tools()));
        value.put("executionCallCounts",Map.of("workflow",0,"a2a",0,"managed",0));
        assertThrows(IllegalArgumentException.class, () -> SupervisorWorkflowContinuation.parse(value,tools()));
    }
    @Test void acceptsExactIntegralRepresentations() {
        for (Number version : List.of(3, 3L, new BigInteger("3"), new BigDecimal("3.0"))) {
            var value = continuation(); value.put("continuationSchemaVersion", version);
            value.put("plannedWorkflowCursor", new BigDecimal("0.0"));
            var result = SupervisorWorkflowContinuation.parse(value, tools());
            assertEquals(0, result.plannedWorkflowCursor());
            assertEquals("first", result.waitingToolName());
        }
    }

    @Test void rejectsEmptyDuplicateOrUnpublishedPlans() {
        for (List<String> names : List.of(List.<String>of(), List.of("first", "first"), List.of("outside"))) {
            var value = continuation(); value.put("plannedWorkflowToolNames", names);
            assertThrows(IllegalArgumentException.class, () -> SupervisorWorkflowContinuation.parse(value, tools()));
        }
    }

    @Test void requiresCompletedPrefixAndMatchingWaitingStep() {
        var value = continuation(); value.put("plannedWorkflowCursor", 1);
        assertThrows(IllegalArgumentException.class, () -> SupervisorWorkflowContinuation.parse(value, tools()));
        value.put("waitingToolName", "second");
        assertThrows(IllegalArgumentException.class, () -> SupervisorWorkflowContinuation.parse(value, tools()));
        value.put("completedWorkflowToolNames", List.of("first"));
        value.put("executionCallCounts",Map.of("workflow",2,"a2a",0,"managed",0));
        assertEquals("second", SupervisorWorkflowContinuation.parse(value, tools()).waitingToolName());
        value.put("completedWorkflowToolNames", List.of("first", "second"));
        assertThrows(IllegalArgumentException.class, () -> SupervisorWorkflowContinuation.parse(value, tools()));
    }

    @Test void rejectsRecordedOrderDrift() {
        var value = continuation();
        value.put("recordedPlan", Map.of("workflowToolNames", List.of("second", "first")));
        assertThrows(IllegalArgumentException.class, () -> SupervisorWorkflowContinuation.parse(value, tools()));
    }

    @Test void acceptedContractDoesNotRetainMutableInputLists() {
        var names = new ArrayList<>(List.of("first", "second"));
        var value = continuation(); value.put("plannedWorkflowToolNames", names);
        var result = SupervisorWorkflowContinuation.parse(value, tools());
        names.clear();
        assertEquals(List.of("first", "second"), result.plannedWorkflowToolNames());
        assertThrows(UnsupportedOperationException.class, () -> result.plannedWorkflowToolNames().clear());
    }

    private Map<String, Object> continuation() {
        return new LinkedHashMap<>(Map.of("continuationSchemaVersion", 3, "plannedWorkflowCursor", 0,
                "executionCallCounts",Map.of("workflow",1,"a2a",0,"managed",0),
                "plannedWorkflowToolNames", List.of("first", "second"), "waitingToolName", "first",
                "recordedPlan", Map.of("workflowToolNames", List.of("first", "second")), "completedWorkflowToolNames", List.of()));
    }

    private List<RuntimeAgentWorkflowToolSnapshot> tools() {
        return List.of(tool("first"), tool("second"));
    }

    private RuntimeAgentWorkflowToolSnapshot tool(String name) {
        return RuntimeAgentWorkflowToolSnapshot.builder().toolName(name).build();
    }
}
