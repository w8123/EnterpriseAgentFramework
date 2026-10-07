package com.enterprise.ai.runtime.workflow;

import com.enterprise.ai.agent.graph.GraphSpecToolContract;

import com.enterprise.ai.runtime.client.capability.RuntimeCapabilityCatalogClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.io.InputStream;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RuntimeCapabilityContractPinsTest {
    private final RuntimeCapabilityCatalogClient catalog = mock(RuntimeCapabilityCatalogClient.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private final RuntimeCapabilityContractPins pins = new RuntimeCapabilityContractPins(catalog, mapper);
    private static final String GRAPH = """
            {"nodes":[{"id":"query","type":"TOOL","ref":{"kind":"TOOL","qualifiedName":"orders:query"}}],
             "entryNodeId":"query","exitNodeIds":["query"]}
            """;

    @Test
    void publicationPinsOwnerContractAndRollbackRejectsChangedContract() throws Exception {
        when(catalog.getToolDefinition("orders:query")).thenReturn(definition("a".repeat(64), "READY"));
        String published = pins.pin(GRAPH);
        assertEquals("a".repeat(64), mapper.readTree(published).at("/nodes/0/ref/contractHash").asText());
        assertEquals(101L, mapper.readTree(published).at("/nodes/0/ref/assetId").asLong());
        assertEquals(201L, mapper.readTree(published).at("/nodes/0/ref/acceptedRevisionId").asLong());
        assertEquals("BUSINESS_METHOD", mapper.readTree(published).at("/nodes/0/ref/assetType").asText());
        assertDoesNotThrow(() -> pins.validatePinned(published));
        when(catalog.getToolDefinition("orders:query")).thenReturn(definition("b".repeat(64), "READY"));
        assertThrows(IllegalArgumentException.class, () -> pins.validatePinned(published));
        assertEquals("b".repeat(64), mapper.readTree(pins.pin(published)).at("/nodes/0/ref/contractHash").asText());
    }

    @Test
    void unknownSourceCannotBePublishedAndUnpinnedHistoricalReleaseCannotBeRestored() {
        when(catalog.getToolDefinition("orders:query")).thenReturn(definition("a".repeat(64), "SOURCE_UNKNOWN"));
        assertThrows(IllegalArgumentException.class, () -> pins.pin(GRAPH));
        when(catalog.getToolDefinition("orders:query")).thenReturn(definition("a".repeat(64), "READY"));
        assertThrows(IllegalArgumentException.class, () -> pins.validatePinned(GRAPH));
    }

    @Test
    void retiredManualProjectionExplainsMigrationWithoutMutatingDraftOrFrozenRelease() throws Exception {
        when(catalog.getToolDefinition("orders:query")).thenReturn(definition("a".repeat(64), "READY"));
        String frozen = pins.pin(GRAPH);
        String before = frozen;
        when(catalog.getToolDefinition("orders:query")).thenReturn(definition("a".repeat(64), "LEGACY_SCAN_TOOL_RETIRED"));
        var draftFailure = assertThrows(IllegalArgumentException.class, () -> pins.pin(GRAPH));
        var publishedFailure = assertThrows(IllegalArgumentException.class, () -> pins.validatePinned(frozen));
        assertTrue(draftFailure.getMessage().startsWith("CAPABILITY_LEGACY_SCAN_TOOL_RETIRED:"));
        assertTrue(publishedFailure.getMessage().contains("选择 API"));
        assertEquals(before, frozen);
        assertEquals("orders:query", mapper.readTree(GRAPH).at("/nodes/0/ref/qualifiedName").asText());
    }

    @Test
    void historicalReleaseWithAnotherOwnerDefinitionCannotBeRestored() throws Exception {
        when(catalog.getToolDefinition("orders:query")).thenReturn(definition("a".repeat(64), "READY"));
        var historical = mapper.readTree(pins.pin(GRAPH));
        ((com.fasterxml.jackson.databind.node.ObjectNode) historical.at("/nodes/0/ref"))
                .put("assetId", 102L);
        assertThrows(IllegalArgumentException.class, () -> pins.validatePinned(mapper.writeValueAsString(historical)));
    }

    @Test void publicationRejectsAnAuthorReferenceToAnotherAssetInsteadOfReplacingIt() throws Exception {
        when(catalog.getToolDefinition("orders:query")).thenReturn(definition("a".repeat(64), "READY"));
        var graph = mapper.readTree(GRAPH);
        var ref = (com.fasterxml.jackson.databind.node.ObjectNode) graph.at("/nodes/0/ref");
        ref.put("assetType", "BUSINESS_METHOD").put("assetId", 102L);
        String wrongAsset = mapper.writeValueAsString(graph);
        assertEquals("WORKFLOW_ASSET_REFERENCE_MISMATCH",
                assertThrows(IllegalArgumentException.class, () -> pins.pin(wrongAsset)).getMessage());
        ref.put("assetId", 101L).put("projectCode", "another");
        String wrongProject = mapper.writeValueAsString(graph);
        assertThrows(IllegalArgumentException.class, () -> pins.pin(wrongProject));
        assertEquals(102L, mapper.readTree(wrongAsset).at("/nodes/0/ref/assetId").asLong());
    }

    @Test void publishedExecutionRequiresPinsButDraftCanStillBeRead() {
        assertThrows(IllegalArgumentException.class, () -> GraphSpecToolContract.requirePublishedPins(GRAPH));
        when(catalog.getToolDefinition("orders:query")).thenReturn(definition("a".repeat(64), "READY"));
        assertDoesNotThrow(() -> GraphSpecToolContract.requirePublishedPins(pins.pin(GRAPH)));
        assertThrows(IllegalArgumentException.class, () -> GraphSpecToolContract.requirePublishedPins("null"));
    }

    @Test void publicationRejectsCrossProjectMethodAndChangedAcceptanceEvenWithSameHash() throws Exception {
        when(catalog.getToolDefinition("orders:query")).thenReturn(definition("a".repeat(64), "READY"));
        var workflow = new RuntimeWorkflowDefinitionEntity();
        workflow.setProjectId(42L); workflow.setProjectCode("another");
        assertEquals("BUSINESS_METHOD_WORKFLOW_PROJECT_MISMATCH",
                assertThrows(IllegalArgumentException.class, () -> pins.pin(GRAPH, workflow)).getMessage());
        workflow.setProjectId(41L); workflow.setProjectCode("orders");
        String published = pins.pin(GRAPH, workflow);
        var changed = new java.util.LinkedHashMap<>(definition("a".repeat(64), "READY"));
        changed.put("acceptedRevisionId", 202L);
        when(catalog.getToolDefinition("orders:query")).thenReturn(changed);
        assertThrows(IllegalArgumentException.class, () -> pins.validatePinned(published));
        assertEquals(201L, mapper.readTree(published).at("/nodes/0/ref/acceptedRevisionId").asLong());
        changed = new java.util.LinkedHashMap<>(definition("a".repeat(64), "READY"));
        changed.put("executionRevision", "e".repeat(64));
        when(catalog.getToolDefinition("orders:query")).thenReturn(changed);
        assertThrows(IllegalArgumentException.class, () -> pins.validatePinned(published));
    }

    @Test
    void browserSavedHttpApiMustResolveItsCapabilityOwnerInsteadOfASecondToolDefinition() throws Exception {
        try (InputStream resource = getClass().getResourceAsStream("/bmapi-3c-a/browser-saved-working-copy.json")) {
            assertNotNull(resource);
            String browserGraph = mapper.readTree(resource).path("graphSpecJson").asText();
            String reference = mapper.readTree(browserGraph).at("/nodes/0/ref/qualifiedName").asText();
            assertTrue(reference.startsWith("http-api:orders:dev:"));
            RuntimeWorkflowHttpApiService httpApis = mock(RuntimeWorkflowHttpApiService.class);
            RuntimeCapabilityContractPins apiPins = new RuntimeCapabilityContractPins(catalog, mapper, httpApis);
            RuntimeWorkflowDefinitionEntity workflow = new RuntimeWorkflowDefinitionEntity();
            workflow.setId("wf-api"); workflow.setProjectId(41L); workflow.setProjectCode("orders");
            when(httpApis.pin(any(), eq(workflow), eq(reference))).thenReturn(
                    new RuntimeWorkflowHttpApiService.ReleasePin(201L,
                            "24c4c6a8623e7e22cf06bc8cee00d14827a28719008e7f1f29638913365f7ea4"));
            String published = apiPins.pin(browserGraph, workflow);
            assertEquals("24c4c6a8623e7e22cf06bc8cee00d14827a28719008e7f1f29638913365f7ea4",
                    mapper.readTree(published).at("/nodes/0/ref/contractHash").asText());
            assertEquals(201L, mapper.readTree(published).at("/nodes/0/ref/definitionId").asLong());
            verify(httpApis).pin(any(), eq(workflow), eq(reference));
            verify(catalog, never()).getToolDefinition(reference);
        }
    }

    private Map<String, Object> definition(String hash, String availability) {
        return Map.ofEntries(Map.entry("id", 101L), Map.entry("assetId", 101L), Map.entry("acceptedRevisionId", 201L),
                Map.entry("assetType", "BUSINESS_METHOD"), Map.entry("projectId", 41L), Map.entry("projectCode", "orders"),
                Map.entry("name", "orders_query"), Map.entry("qualifiedName", "orders:query"), Map.entry("contractHash", hash),
                Map.entry("businessContractHash", hash), Map.entry("bindingHash", "c".repeat(64)),
                Map.entry("executionRevision", "d".repeat(64)),
                Map.entry("enabled", true), Map.entry("sourceAvailability", availability));
    }
}
