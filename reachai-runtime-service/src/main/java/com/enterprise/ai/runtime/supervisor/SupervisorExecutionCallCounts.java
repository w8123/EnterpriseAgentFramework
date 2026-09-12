package com.enterprise.ai.runtime.supervisor;

import java.math.BigDecimal;
import java.util.Map;

/** Exact cumulative call-budget consumption carried by an owner-validated continuation. */
record SupervisorExecutionCallCounts(int workflow, int a2a, int managed) {
    static SupervisorExecutionCallCounts parse(Object value) {
        if (!(value instanceof Map<?, ?> counts)) throw new IllegalArgumentException("executionCallCounts is required");
        return new SupervisorExecutionCallCounts(count(counts.get("workflow")), count(counts.get("a2a")), count(counts.get("managed")));
    }

    void validateBudget(int maximum) {
        if ((long) workflow + a2a + managed > maximum) {
            throw new IllegalArgumentException("saved execution call counts exceed the published call budget");
        }
    }

    Map<String, Integer> snapshot() {
        return Map.of("workflow",workflow,"a2a",a2a,"managed",managed);
    }

    private static int count(Object value) {
        if (!(value instanceof Number)) throw new IllegalArgumentException("saved execution call count is required");
        try {
            int count = new BigDecimal(value.toString()).intValueExact();
            if (count < 0) throw new IllegalArgumentException("saved execution call count cannot be negative");
            return count;
        } catch (ArithmeticException | NumberFormatException invalid) {
            throw new IllegalArgumentException("saved execution call count must be an exact integer");
        }
    }
}
