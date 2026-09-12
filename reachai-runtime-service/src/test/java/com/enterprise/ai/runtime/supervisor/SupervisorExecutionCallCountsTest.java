package com.enterprise.ai.runtime.supervisor;

import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class SupervisorExecutionCallCountsTest {
    @Test void rejectsMissingNegativeFractionalAndOverflowedCounters() {
        assertThrows(IllegalArgumentException.class, () -> SupervisorExecutionCallCounts.parse(null));
        assertThrows(IllegalArgumentException.class, () -> SupervisorExecutionCallCounts.parse(Map.of()));
        for (Object invalid : List.of(-1, 1.5, 4294967297L, "1")) {
            for (String key : List.of("workflow","a2a","managed")) {
                var counts = new LinkedHashMap<String,Object>(Map.of("workflow",1,"a2a",0,"managed",0));
                counts.put(key,invalid);
                assertThrows(IllegalArgumentException.class, () -> SupervisorExecutionCallCounts.parse(counts));
            }
        }
    }

    @Test void validatesTheCombinedBudgetWithoutIntegerOverflow() {
        var counts = SupervisorExecutionCallCounts.parse(Map.of("workflow",new BigDecimal("2.0"),"a2a",1L,"managed",1));
        counts.validateBudget(4);
        assertThrows(IllegalArgumentException.class, () -> counts.validateBudget(3));
        var huge = SupervisorExecutionCallCounts.parse(Map.of("workflow",Integer.MAX_VALUE,"a2a",1,"managed",0));
        assertThrows(IllegalArgumentException.class, () -> huge.validateBudget(Integer.MAX_VALUE));
        assertEquals(Map.of("workflow",2,"a2a",1,"managed",1),counts.snapshot());
        assertThrows(UnsupportedOperationException.class, () -> counts.snapshot().clear());
    }
}
