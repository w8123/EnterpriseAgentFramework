package com.enterprise.ai.capability.catalog.scan;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CapabilityScanProjectAgentReferenceReaderTest {

    @Test
    void readsAgentToolReferencesThroughRuntimeInternalClient() {
        CapabilityRuntimeAgentReferenceClient client = mock(CapabilityRuntimeAgentReferenceClient.class);
        CapabilityScanProjectAgentReferenceReader reader = new CapabilityScanProjectAgentReferenceReader(client);
        when(client.listAgentToolReferences()).thenReturn(List.of(
                new CapabilityRuntimeAgentReferenceClient.AgentToolReferenceView(
                        "agent-1",
                        "Team Assistant",
                        List.of("orders_create"),
                        List.of("orders_skill"))
        ));

        List<com.enterprise.ai.agent.capability.catalog.scan.ScanProjectAgentReferenceReader.AgentToolReference> refs =
                reader.listAgentToolReferences();

        assertEquals("agent-1", refs.get(0).agentId());
        assertEquals(List.of("orders_create"), refs.get(0).tools());
    }
}
