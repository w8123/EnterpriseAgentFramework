package com.enterprise.ai.agent.registry;

import com.enterprise.ai.agent.acl.ToolAclMapper;
import com.enterprise.ai.agent.agent.AgentDefinition;
import com.enterprise.ai.agent.agent.AgentDefinitionService;
import com.enterprise.ai.agent.graph.GraphSpec;
import com.enterprise.ai.agent.registry.RegistryContracts.AgentGraphRegistration;
import com.enterprise.ai.agent.registry.RegistryContracts.AgentGraphSyncRequest;
import com.enterprise.ai.agent.registry.RegistryContracts.AgentGraphSyncResponse;
import com.enterprise.ai.agent.scan.ScanModuleService;
import com.enterprise.ai.agent.scan.ScanProjectEntity;
import com.enterprise.ai.agent.scan.ScanProjectMapper;
import com.enterprise.ai.agent.scan.ScanProjectService;
import com.enterprise.ai.agent.scan.ScanProjectToolService;
import com.enterprise.ai.agent.tools.definition.ToolDefinitionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AiRegistryServiceAgentGraphSyncTest {

    private ScanProjectService scanProjectService;
    private AgentDefinitionService agentDefinitionService;
    private AiRegistryService service;
    private ScanProjectEntity project;

    @BeforeEach
    void setUp() {
        scanProjectService = mock(ScanProjectService.class);
        agentDefinitionService = mock(AgentDefinitionService.class);
        service = new AiRegistryService(
                scanProjectService,
                mock(ScanProjectMapper.class),
                mock(ScanProjectToolService.class),
                mock(ScanModuleService.class),
                mock(ProjectInstanceMapper.class),
                mock(CapabilitySyncLogMapper.class),
                mock(CapabilitySnapshotMapper.class),
                mock(CapabilityDiffItemMapper.class),
                mock(CapabilityApplyRecordMapper.class),
                mock(ToolDefinitionService.class),
                agentDefinitionService,
                mock(ToolAclMapper.class),
                mock(RegistrySecurityService.class),
                new ObjectMapper());
        project = new ScanProjectEntity();
        project.setId(7L);
        project.setProjectCode("order-service");
        project.setName("Order Service");
        project.setVisibility("PROJECT");
        when(scanProjectService.findByProjectCode("order-service")).thenReturn(Optional.of(project));
    }

    @Test
    void syncCreatesAgentDraftFromSdkGraph() {
        when(agentDefinitionService.findByKeySlug("order-service-order_assistant")).thenReturn(Optional.empty());
        when(agentDefinitionService.create(any())).thenAnswer(invocation -> {
            AgentDefinition def = invocation.getArgument(0);
            def.setId("agent-1");
            return def;
        });

        AgentGraphSyncResponse response = service.syncAgentGraphs("order-service",
                new AgentGraphSyncRequest("sync-1", "SDK", true, List.of(registration())));

        assertEquals(1, response.created());
        assertEquals(0, response.updated());
        assertEquals("agent-1", response.items().get(0).agentId());
        verify(agentDefinitionService).create(any(AgentDefinition.class));
    }

    @Test
    void syncUpdatesExistingDraftWithoutPublishing() {
        AgentDefinition existing = AgentDefinition.builder()
                .id("agent-1")
                .keySlug("order-service-order_assistant")
                .name("Old")
                .modelInstanceId("old-model")
                .maxSteps(5)
                .enabled(true)
                .build();
        when(agentDefinitionService.findByKeySlug("order-service-order_assistant")).thenReturn(Optional.of(existing));
        when(agentDefinitionService.update(org.mockito.ArgumentMatchers.anyString(), any())).thenAnswer(invocation -> {
            AgentDefinition update = invocation.getArgument(1);
            update.setId("agent-1");
            return update;
        });

        AgentGraphSyncResponse response = service.syncAgentGraphs("order-service",
                new AgentGraphSyncRequest("sync-2", "SDK", true, List.of(registration())));

        assertEquals(0, response.created());
        assertEquals(1, response.updated());
        verify(agentDefinitionService).update(any(), any(AgentDefinition.class));
    }

    @Test
    void upsertPayloadIncludesGraphSpecCanvasAndSdkMetadata() {
        when(agentDefinitionService.findByKeySlug("order-service-order_assistant")).thenReturn(Optional.empty());
        when(agentDefinitionService.create(any())).thenAnswer(invocation -> invocation.getArgument(0));

        service.syncAgentGraphs("order-service",
                new AgentGraphSyncRequest("sync-3", "SDK", true, List.of(registration())));

        verify(agentDefinitionService).create(org.mockito.ArgumentMatchers.argThat(def -> {
            assertEquals("LANGGRAPH4J", def.getRuntimeType());
            assertEquals("CENTRAL", def.getRuntimePlacement());
            assertEquals("llm-1", def.getModelInstanceId());
            assertEquals("order-service-order_assistant", def.getKeySlug());
            assertEquals(List.of("queryOrder"), def.getTools());
            assertNotNull(def.getGraphSpec());
            assertTrue(def.getCanvasJson().contains("\"type\":\"llm\""));
            assertTrue(def.getCanvasJson().contains("\"x\":520"));
            assertTrue(def.getCanvasJson().contains("\"y\":260"));
            assertTrue(def.getCanvasJson().contains("\"animated\":true"));
            assertTrue(def.getExtra().containsKey("sdkGraph"));
            @SuppressWarnings("unchecked")
            Map<String, Object> sdkGraph = (Map<String, Object>) def.getExtra().get("sdkGraph");
            assertEquals("SDK", sdkGraph.get("managedBy"));
            assertEquals("DRAFT_ONLY", sdkGraph.get("overwriteMode"));
            return true;
        }));
    }

    private AgentGraphRegistration registration() {
        GraphSpec graphSpec = GraphSpec.builder()
                .code("order_assistant")
                .name("Order Assistant")
                .runtimeHint("LANGGRAPH4J")
                .entry("classify")
                .finishNode("queryOrder")
                .node(GraphSpec.Node.builder()
                        .id("classify")
                        .type("LLM")
                        .name("Classify")
                        .config(Map.of("modelInstanceId", "llm-1"))
                        .build())
                .node(GraphSpec.Node.builder()
                        .id("queryOrder")
                        .type("TOOL")
                        .name("Query Order")
                        .ref(GraphSpec.CapabilityRef.builder()
                                .kind("TOOL")
                                .name("queryOrder")
                                .qualifiedName("order-service:queryOrder")
                                .projectCode("order-service")
                                .build())
                        .config(Map.of(
                                "outputAlias", "order",
                                "description", "Query order details from order service.",
                                "ui", Map.of("position", Map.of("x", 520, "y", 260))))
                        .build())
                .edge(GraphSpec.Edge.builder().from("START").to("classify").condition("always").build())
                .edge(GraphSpec.Edge.builder().from("classify").to("queryOrder").condition("success").build())
                .edge(GraphSpec.Edge.builder().from("queryOrder").to("END").condition("always").build())
                .build();
        return new AgentGraphRegistration(
                "order_assistant",
                "Order Assistant",
                "SDK graph",
                "LANGGRAPH4J",
                null,
                "Help with order questions.",
                "PROJECT",
                graphSpec,
                Map.of("owner", "sdk-test"));
    }
}
