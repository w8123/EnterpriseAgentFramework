package com.enterprise.ai.runtime.workflow;

import com.enterprise.ai.agent.graph.GraphSpecToolContract;

import com.enterprise.ai.runtime.client.capability.RuntimeCapabilityCatalogClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
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

    @Test void publishedExecutionRequiresPinsButDraftCanStillBeRead() {
        assertThrows(IllegalArgumentException.class, () -> GraphSpecToolContract.requirePublishedPins(GRAPH));
        when(catalog.getToolDefinition("orders:query")).thenReturn(definition("a".repeat(64), "READY"));
        assertDoesNotThrow(() -> GraphSpecToolContract.requirePublishedPins(pins.pin(GRAPH)));
        assertThrows(IllegalArgumentException.class, () -> GraphSpecToolContract.requirePublishedPins("null"));
    }

    private Map<String, Object> definition(String hash, String availability) {
        return Map.of("name", "orders_query", "qualifiedName", "orders:query", "contractHash", hash,
                "enabled", true, "sourceAvailability", availability);
    }
}
