package com.enterprise.ai.runtime.workflow.node;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuntimeWorkflowNodeCapabilityRegistryTest {

    private final RuntimeWorkflowNodeCapabilityRegistry registry = new RuntimeWorkflowNodeCapabilityRegistry();

    @Test
    void stablePhase1VariableNodesStayOpen() {
        for (String type : List.of(
                "VARIABLE_ASSIGN", "TEMPLATE", "VARIABLE_AGGREGATOR")) {
            RuntimeWorkflowNodeCapabilityDescriptor item = registry.find(type).orElseThrow();
            assertEquals(WorkflowNodeMaturity.STABLE, item.maturity());
            assertTrue(item.runtimeExecutable());
            assertTrue(item.publishable());
            assertTrue(item.studioEnabled());
            assertTrue(item.aiAuthoringEnabled());
        }
    }

    @Test
    void knowledgeAndHttpAreBetaOpenAfterSecurityScopeClosed() {
        for (String type : List.of("KNOWLEDGE_RETRIEVAL", "HTTP_REQUEST")) {
            RuntimeWorkflowNodeCapabilityDescriptor item = registry.find(type).orElseThrow();
            assertEquals(WorkflowNodeMaturity.BETA, item.maturity());
            assertTrue(item.runtimeExecutable());
            assertTrue(item.publishable());
            assertTrue(item.studioEnabled());
            assertTrue(item.aiAuthoringEnabled());
        }
    }

    @Test
    void loopIsTemporarilyOpenForBrowserLiveE2E() {
        RuntimeWorkflowNodeCapabilityDescriptor item = registry.find("LOOP").orElseThrow();
        assertEquals(WorkflowNodeMaturity.BETA, item.maturity());
        assertTrue(item.runtimeExecutable());
        assertTrue(item.publishable());
        assertTrue(item.studioEnabled());
        assertTrue(item.aiAuthoringEnabled());
    }

    @Test
    void plannedNodesStayClosed() {
        List<RuntimeWorkflowNodeCapabilityDescriptor> planned = registry.allCatalog().stream()
                .filter(item -> item.maturity() == WorkflowNodeMaturity.PLANNED)
                .toList();
        assertFalse(planned.isEmpty());
        for (RuntimeWorkflowNodeCapabilityDescriptor item : planned) {
            assertFalse(item.runtimeExecutable(), item.type());
            assertFalse(item.publishable(), item.type());
            assertFalse(item.studioEnabled(), item.type());
            assertFalse(item.aiAuthoringEnabled(), item.type());
            assertTrue(item.unavailableReason() != null && !item.unavailableReason().isBlank(), item.type());
        }
    }

    @Test
    void interactionExposesOnlyDisplayOnlyPresentOutputVariant() {
        RuntimeWorkflowNodeCapabilityDescriptor item = registry.find("INTERACTION").orElseThrow();
        assertEquals(WorkflowNodeMaturity.BETA, item.maturity());
        assertTrue(item.runtimeExecutable());
        assertFalse(item.publishable());
        assertTrue(item.studioEnabled());
        assertTrue(item.aiAuthoringEnabled());
        assertEquals(List.of("PRESENT_OUTPUT"), item.enabledVariants());
        assertTrue(registry.isPublishable("INTERACTION", java.util.Map.of(
                "interactionType", "PRESENT_OUTPUT")));
        assertTrue(registry.isAiAuthoringEnabled("INTERACTION", java.util.Map.of(
                "interactionType", "present-output")));
        assertFalse(registry.isPublishable("INTERACTION", java.util.Map.of(
                "interactionType", "COLLECT_INPUT")));
        assertFalse(registry.isAiAuthoringEnabled("INTERACTION", java.util.Map.of(
                "interactionType", "CONFIRM_ACTION")));
        assertTrue(item.unavailableReason() != null && item.unavailableReason().contains("pause/resume"));
    }

    @Test
    void pageActionIsPublishableBeta() {
        RuntimeWorkflowNodeCapabilityDescriptor item = registry.find("PAGE_ACTION").orElseThrow();
        assertEquals(WorkflowNodeMaturity.BETA, item.maturity());
        assertTrue(item.runtimeExecutable());
        assertTrue(item.publishable());
        assertTrue(item.studioEnabled());
        assertTrue(item.aiAuthoringEnabled());
    }

    @Test
    void studioAndAiAuthoringCatalogsExcludeClosedNodes() {
        Set<String> studio = registry.studioCatalog().stream()
                .map(RuntimeWorkflowNodeCapabilityDescriptor::type)
                .collect(Collectors.toSet());
        Set<String> authoring = registry.aiAuthoringCatalog().stream()
                .map(RuntimeWorkflowNodeCapabilityDescriptor::type)
                .collect(Collectors.toSet());
        assertTrue(studio.contains("INTERACTION"));
        assertFalse(studio.contains("CODE"));
        assertTrue(studio.contains("LOOP"));
        assertTrue(authoring.contains("INTERACTION"));
        assertFalse(authoring.contains("HUMAN_APPROVAL"));
        assertTrue(authoring.contains("LOOP"));
        assertTrue(studio.contains("PAGE_ACTION"));
        assertTrue(authoring.contains("PAGE_ACTION"));
        assertTrue(authoring.contains("LLM"));
        assertTrue(studio.contains("VARIABLE_ASSIGN"));
        assertTrue(studio.contains("TEMPLATE"));
        assertTrue(studio.contains("VARIABLE_AGGREGATOR"));
        assertTrue(studio.contains("KNOWLEDGE_RETRIEVAL"));
        assertTrue(studio.contains("HTTP_REQUEST"));
        assertEquals(15, studio.size());
    }

    @Test
    void findsByCanonicalTypeAndCanvasKindOnly() {
        assertEquals("TOOL", registry.find("tool").orElseThrow().type());
        assertTrue(registry.find("skill").isEmpty());
        assertEquals("INTENT_CLASSIFIER", registry.find("classifier").orElseThrow().type());
        assertEquals("PAGE_ACTION", registry.find("pageAction").orElseThrow().type());
        assertEquals("LOOP", registry.find("loop").orElseThrow().type());
        assertTrue(registry.find("ui_action").isEmpty());
        assertTrue(registry.find("form_input").isEmpty());
    }
}
