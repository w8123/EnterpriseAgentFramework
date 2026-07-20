package com.enterprise.ai.runtime.execution.context;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkflowVariableNamespacesTest {

    @Test
    void acceptsValidMultiSegmentPath() {
        assertEquals("var.order.status", WorkflowVariableNamespaces.normalizeBusinessWriteTarget("order.status"));
        assertEquals("var.foo_bar", WorkflowVariableNamespaces.normalizeBusinessWriteTarget("var.foo_bar"));
    }

    @Test
    void rejectsEmptySegmentsAndBadKeys() {
        assertThrows(IllegalArgumentException.class,
                () -> WorkflowVariableNamespaces.normalizeBusinessWriteTarget("foo..bar"));
        assertThrows(IllegalArgumentException.class,
                () -> WorkflowVariableNamespaces.normalizeBusinessWriteTarget("foo.bad-key"));
        assertThrows(IllegalArgumentException.class,
                () -> WorkflowVariableNamespaces.normalizeBusinessWriteTarget(".foo"));
        assertThrows(IllegalArgumentException.class,
                () -> WorkflowVariableNamespaces.normalizeBusinessWriteTarget("foo."));
        assertThrows(IllegalArgumentException.class,
                () -> WorkflowVariableNamespaces.normalizeBusinessWriteTarget(""));
        IllegalArgumentException reserved = assertThrows(IllegalArgumentException.class,
                () -> WorkflowVariableNamespaces.normalizeBusinessWriteTarget("sys.x"));
        assertTrue(reserved.getMessage().toLowerCase().contains("reserved"));
    }
}
