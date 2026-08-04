package com.enterprise.ai.runtime.registry;

import com.enterprise.ai.agent.graph.GraphSpec;
import com.enterprise.ai.runtime.client.capability.RuntimeCapabilityCatalogClient;
import com.enterprise.ai.runtime.registry.RuntimeAgentGraphSyncContracts.AgentGraphRegistration;
import com.enterprise.ai.runtime.registry.RuntimeAgentGraphSyncContracts.AgentGraphSyncRequest;
import com.enterprise.ai.runtime.registry.RuntimeAgentGraphSyncContracts.AgentGraphSyncResponse;
import com.enterprise.ai.runtime.workflow.RuntimeWorkflowDefinitionEntity;
import com.enterprise.ai.runtime.workflow.RuntimeWorkflowDefinitionService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.mockito.ArgumentCaptor;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RuntimeAgentGraphSyncServiceTest {

    @Test
    void diffOnlyReportsWorkflowThatWouldBeCreatedWithoutWritingRuntimeTables() {
        RuntimeCapabilityCatalogClient capabilityClient = mock(RuntimeCapabilityCatalogClient.class);
        RuntimeWorkflowDefinitionService workflowService = mock(RuntimeWorkflowDefinitionService.class);
        RuntimeAgentGraphSyncService service =
                new RuntimeAgentGraphSyncService(capabilityClient, workflowService, new ObjectMapper());
        when(capabilityClient.getProject("orders")).thenReturn(Map.of("projectId", 7L, "projectCode", "orders"));
        when(workflowService.findByKeySlug("orders_orderAssistant")).thenReturn(Optional.empty());

        AgentGraphSyncResponse response = service.sync("orders", new AgentGraphSyncRequest(
                "sync-1",
                "SDK",
                false,
                List.of(graph("orderAssistant", "gpt-4o-mini"))
        ));

        assertEquals("sync-1", response.syncId());
        assertEquals(7L, response.projectId());
        assertEquals("orders", response.projectCode());
        assertEquals(1, response.received());
        assertEquals(0, response.created());
        assertEquals(0, response.updated());
        assertEquals("WOULD_CREATE", response.items().get(0).changeType());
    }

    @Test
    void applyCreatesCanonicalSdkWorkflowAndLayoutOnlyCanvas() throws Exception {
        RuntimeCapabilityCatalogClient capabilityClient = mock(RuntimeCapabilityCatalogClient.class);
        RuntimeWorkflowDefinitionService workflowService = mock(RuntimeWorkflowDefinitionService.class);
        ObjectMapper objectMapper = new ObjectMapper();
        RuntimeAgentGraphSyncService service =
                new RuntimeAgentGraphSyncService(capabilityClient, workflowService, objectMapper);
        when(capabilityClient.getProject("orders")).thenReturn(Map.of("projectId", 7L, "projectCode", "orders"));
        when(workflowService.findByKeySlug("orders_orderAssistant")).thenReturn(Optional.empty());
        RuntimeWorkflowDefinitionEntity saved = new RuntimeWorkflowDefinitionEntity();
        saved.setId("wf_1");
        saved.setKeySlug("orders_orderAssistant");
        when(workflowService.create(any())).thenReturn(saved);

        AgentGraphSyncResponse response = service.sync("orders", new AgentGraphSyncRequest(
                "sync-1",
                "SDK",
                true,
                List.of(graph("orderAssistant", "gpt-4o-mini"))
        ));

        assertEquals(1, response.created());
        assertEquals(0, response.updated());
        assertEquals("CREATED", response.items().get(0).changeType());
        assertEquals("wf_1", response.items().get(0).workflowId());
        ArgumentCaptor<RuntimeWorkflowDefinitionEntity> draftCaptor =
                ArgumentCaptor.forClass(RuntimeWorkflowDefinitionEntity.class);
        verify(workflowService).create(draftCaptor.capture());
        RuntimeWorkflowDefinitionEntity draft = draftCaptor.getValue();
        assertEquals("GENERAL", draft.getWorkflowKind());
        assertEquals("GRAPH_SPEC", draft.getExecutionEngine());
        assertEquals("SDK", draft.getDefinitionAuthority());
        assertEquals("SDK_SYNC", draft.getCreationChannel());

        JsonNode graphSpec = objectMapper.readTree(draft.getGraphSpecJson());
        assertEquals(2, graphSpec.path("schemaVersion").asInt());
        assertEquals("llm_1", graphSpec.path("entryNodeId").asText());
        assertEquals("llm_1", graphSpec.path("exitNodeIds").get(0).asText());
        assertFalse(graphSpec.has("entry"));
        assertFalse(graphSpec.has("finish"));
        assertFalse(graphSpec.has("runtimeHint"));

        JsonNode canvas = objectMapper.readTree(draft.getCanvasJson());
        assertEquals(1, canvas.path("schemaVersion").asInt());
        assertTrue(canvas.path("nodes").isArray());
        assertTrue(canvas.path("nodes").findValues("data").isEmpty());
        assertTrue(canvas.path("nodes").findValues("type").isEmpty());
    }

    @Test
    void rejectsGraphWithoutLlmNode() {
        RuntimeCapabilityCatalogClient capabilityClient = mock(RuntimeCapabilityCatalogClient.class);
        RuntimeWorkflowDefinitionService workflowService = mock(RuntimeWorkflowDefinitionService.class);
        RuntimeAgentGraphSyncService service =
                new RuntimeAgentGraphSyncService(capabilityClient, workflowService, new ObjectMapper());
        when(capabilityClient.getProject("orders")).thenReturn(Map.of("projectId", 7L, "projectCode", "orders"));
        GraphSpec spec = GraphSpec.builder()
                .node(GraphSpec.Node.builder().id("tool_1").type("TOOL").build())
                .build();

        AgentGraphSyncRequest request = new AgentGraphSyncRequest(
                "sync-1",
                "SDK",
                true,
                List.of(new AgentGraphRegistration(
                        "orderAssistant",
                        "Order Assistant",
                        "Handles orders",
                        null,
                        null,
                        null,
                        null,
                        spec,
                        Map.of()
                ))
        );

        assertThrows(IllegalArgumentException.class, () -> service.sync("orders", request));
    }

    private AgentGraphRegistration graph(String code, String modelInstanceId) {
        GraphSpec spec = GraphSpec.builder()
                .node(GraphSpec.Node.builder()
                        .id("llm_1")
                        .type("LLM")
                        .name("Answer")
                        .config(Map.of("modelInstanceId", modelInstanceId))
                        .build())
                .entryNodeId("llm_1")
                .exitNodeIds(List.of("llm_1"))
                .build();
        return new AgentGraphRegistration(
                code,
                "Order Assistant",
                "Handles orders",
                null,
                null,
                null,
                null,
                spec,
                Map.of("team", "ops")
        );
    }
}
