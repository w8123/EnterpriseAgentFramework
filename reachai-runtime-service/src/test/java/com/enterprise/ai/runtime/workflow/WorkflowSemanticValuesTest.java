package com.enterprise.ai.runtime.workflow;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class WorkflowSemanticValuesTest {

    @Test
    void resolvesCanonicalDefaults() {
        WorkflowSemanticValues.Resolved resolved = WorkflowSemanticValues.resolve(
                null, null, null, null, null);

        assertEquals("GENERAL", resolved.workflowKind());
        assertEquals("GRAPH_SPEC", resolved.executionEngine());
        assertEquals("USER", resolved.definitionAuthority());
        assertEquals("STUDIO", resolved.creationChannel());
    }

    @Test
    void resolvesPageAssistantCreatedByAiCoding() {
        WorkflowSemanticValues.Resolved resolved = WorkflowSemanticValues.resolve(
                "PAGE_ASSISTANT", "GRAPH_SPEC", "USER", "AI_CODING",
                null);

        assertEquals("PAGE_ASSISTANT", resolved.workflowKind());
        assertEquals("USER", resolved.definitionAuthority());
        assertEquals("AI_CODING", resolved.creationChannel());
    }

    @Test
    void rejectsRemovedSemanticValues() {
        assertThrows(IllegalArgumentException.class,
                () -> WorkflowSemanticValues.normalizeWorkflowKind("CHAT"));
        assertThrows(IllegalArgumentException.class,
                () -> WorkflowSemanticValues.normalizeWorkflowKind("SDK_GRAPH"));
        assertThrows(IllegalArgumentException.class,
                () -> WorkflowSemanticValues.normalizeExecutionEngine("LANGGRAPH4J"));
        assertThrows(IllegalArgumentException.class,
                () -> WorkflowSemanticValues.normalizeDefinitionAuthority("MANUAL"));
        assertThrows(IllegalArgumentException.class,
                () -> WorkflowSemanticValues.normalizeCreationChannel("SDK_ONBOARDING"));
        assertThrows(IllegalArgumentException.class,
                () -> WorkflowSemanticValues.normalizeCreationChannel("PAGE_ASSISTANT_WIZARD"));
    }

    @Test
    void rejectsUnknownExecutionEngine() {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> WorkflowSemanticValues.normalizeExecutionEngine("some-new-library"));

        assertEquals("unsupported executionEngine: some-new-library", error.getMessage());
    }
}
