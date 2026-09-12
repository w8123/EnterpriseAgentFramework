package com.enterprise.ai.runtime.registry;

import com.enterprise.ai.agent.graph.GraphSpec;
import com.enterprise.ai.runtime.client.capability.RuntimeCapabilityCatalogClient;
import com.enterprise.ai.runtime.registry.RuntimeAgentGraphSyncContracts.AgentGraphRegistration;
import com.enterprise.ai.runtime.registry.RuntimeAgentGraphSyncContracts.AgentGraphSyncRequest;
import com.enterprise.ai.runtime.workflow.RuntimeSdkWorkflowSyncService;
import com.enterprise.ai.runtime.workflow.RuntimeSdkWorkflowSyncService.GraphReceipt;
import com.enterprise.ai.runtime.workflow.RuntimeSdkWorkflowSyncService.SyncGraph;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RuntimeAgentGraphSyncServiceTest {
    @Test
    void diffOnlyMapsOwnerReceiptsWithoutReportingWrites() {
        var projects = mock(RuntimeCapabilityCatalogClient.class);
        var owner = mock(RuntimeSdkWorkflowSyncService.class);
        var registry = new RuntimeAgentGraphSyncService(projects, owner, new ObjectMapper());
        when(projects.getProject("orders")).thenReturn(Map.of("projectId", 7L, "projectCode", "orders"));
        when(owner.sync(any(), any(), any(), anyBoolean(), any())).thenReturn(List.of(
                new GraphReceipt("helper", null, "orders_helper", true),
                new GraphReceipt("existing", "wf-1", "orders_existing", false)));
        var result = registry.sync(" orders ", new AgentGraphSyncRequest(" sync-1 ", "SDK", false,
                List.of(graph("helper"), graph("existing"))));
        assertEquals(7L, result.projectId());
        assertEquals("sync-1", result.syncId());
        assertEquals(2, result.received());
        assertEquals(0, result.created());
        assertEquals(0, result.updated());
        assertEquals(List.of("WOULD_CREATE", "WOULD_UPDATE"), result.items().stream().map(item -> item.changeType()).toList());
        verify(owner).sync(eq(7L), eq("orders"), eq("sync-1"), eq(false), any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void applyFreezesProtocolDocumentsAndMapsOwnerResults() throws Exception {
        var projects = mock(RuntimeCapabilityCatalogClient.class);
        var owner = mock(RuntimeSdkWorkflowSyncService.class);
        var json = new ObjectMapper();
        var registry = new RuntimeAgentGraphSyncService(projects, owner, json);
        when(projects.getProject("orders")).thenReturn(Map.of("projectId", 7L, "projectCode", "orders"));
        when(owner.sync(any(), any(), any(), anyBoolean(), any())).thenReturn(List.of(
                new GraphReceipt("helper", "wf-1", "orders_helper", true)));
        var graph = graph("helper");
        var result = registry.sync("orders", new AgentGraphSyncRequest("sync-1", "caller-source", null, List.of(graph)));
        assertEquals(1, result.created());
        assertEquals(0, result.updated());
        assertEquals("wf-1", result.items().get(0).workflowId());
        assertEquals("CREATED", result.items().get(0).changeType());
        ArgumentCaptor<List<SyncGraph>> commands = ArgumentCaptor.forClass(List.class);
        verify(owner).sync(eq(7L), eq("orders"), eq("sync-1"), eq(true), commands.capture());
        var frozen = commands.getValue().get(0);
        graph.graphSpec().setEntryNodeId("changed-after-sync");
        assertEquals("answer", json.readTree(frozen.graphSpecJson()).path("entryNodeId").asText());
        assertEquals("ops", json.readTree(frozen.metadataJson()).path("team").asText());
        assertThrows(UnsupportedOperationException.class, () -> commands.getValue().clear());
    }

    @Test
    void missingGraphIsRejectedBeforeCallingTheOwner() {
        var projects = mock(RuntimeCapabilityCatalogClient.class);
        var owner = mock(RuntimeSdkWorkflowSyncService.class);
        when(projects.getProject("orders")).thenReturn(Map.of("projectId", 7L, "projectCode", "orders"));
        var registry = new RuntimeAgentGraphSyncService(projects, owner, new ObjectMapper());
        var missing = new AgentGraphRegistration("helper", "Helper", null, null, null, null, null, null, Map.of());
        assertThrows(IllegalArgumentException.class, () -> registry.sync("orders",
                new AgentGraphSyncRequest("sync-1", "SDK", true, List.of(missing))));
        verifyNoInteractions(owner);
    }

    private AgentGraphRegistration graph(String code) {
        var spec = GraphSpec.builder().node(GraphSpec.Node.builder().id("answer").type("LLM")
                .config(Map.of("modelInstanceId", "model-a")).build())
                .entryNodeId("answer").exitNodeIds(List.of("answer")).build();
        return new AgentGraphRegistration(code, "Helper", null, null, null, null, null, spec, Map.of("team", "ops"));
    }
}
