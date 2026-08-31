package com.enterprise.ai.reach.sdk.graph;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReachVarsTest {

    @Test
    void formatsAndTrimsInputReference() {
        assertEquals("${input.x}", ReachVars.input(" x "));
    }

    @Test
    void formatsAndTrimsVariableReference() {
        assertEquals("${var.x}", ReachVars.var(" x "));
    }

    @Test
    void formatsAndTrimsNodeOutputReference() {
        assertEquals("${nodeOutput.n1}", ReachVars.nodeOutput(" n1 "));
    }

    @Test
    void rejectsNullEmptyAndBlankReferenceNamesWithContext() {
        String[] invalidValues = {null, "", "   "};
        for (String invalid : invalidValues) {
            IllegalArgumentException inputException = assertThrows(
                    IllegalArgumentException.class, () -> ReachVars.input(invalid));
            assertTrue(inputException.getMessage().contains("input name"));

            IllegalArgumentException variableException = assertThrows(
                    IllegalArgumentException.class, () -> ReachVars.var(invalid));
            assertTrue(variableException.getMessage().contains("variable name"));

            IllegalArgumentException nodeException = assertThrows(
                    IllegalArgumentException.class, () -> ReachVars.nodeOutput(invalid));
            assertTrue(nodeException.getMessage().contains("node id"));
        }
    }

    @Test
    void constantValuesPreserveTextAndMapNullToEmpty() {
        assertEquals("const:", ReachVars.constValue(null));
        assertEquals("const:", ReachVars.constValue(""));
        assertEquals("const:hello", ReachVars.constValue("hello"));
        assertEquals("const: hello ", ReachVars.constValue(" hello "));
    }
}
