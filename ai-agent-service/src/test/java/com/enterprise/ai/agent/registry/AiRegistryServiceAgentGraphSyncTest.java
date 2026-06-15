package com.enterprise.ai.agent.registry;

import com.enterprise.ai.agent.acl.ToolAclMapper;
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
import com.enterprise.ai.agent.workflow.WorkflowDefinitionEntity;
import com.enterprise.ai.agent.workflow.WorkflowDefinitionService;
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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AiRegistryServiceAgentGraphSyncTest {

    private ScanProjectService scanProjectService;
    private AgentDefinitionService agentDefinitionService;
    private WorkflowDefinitionService workflowDefinitionService;
    private AiRegistryService service;
    private ScanProjectEntity project;

    @BeforeEach
    void setUp() {
        scanProjectService = mock(ScanProjectService.class);
        agentDefinitionService = mock(AgentDefinitionService.class);
        workflowDefinitionService = mock(WorkflowDefinitionService.class);
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
                workflowDefinitionService,
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
    void syncCreatesWorkflowDraftFromSdkGraph() {
        when(workflowDefinitionService.findByKeySlug("order-service-order_assistant")).thenReturn(Optional.empty());
        when(workflowDefinitionService.create(any())).thenAnswer(invocation -> {
            WorkflowDefinitionEntity workflow = invocation.getArgument(0);
            workflow.setId("workflow-1");
            return workflow;
        });

        AgentGraphSyncResponse response = service.syncAgentGraphs("order-service",
                new AgentGraphSyncRequest("sync-1", "SDK", true, List.of(registration())));

        assertEquals(1, response.created());
        assertEquals(0, response.updated());
        assertEquals("workflow-1", response.items().get(0).workflowId());
        verify(workflowDefinitionService).create(any(WorkflowDefinitionEntity.class));
        verify(agentDefinitionService, never()).create(any());
    }

    @Test
    void syncUpdatesExistingDraftWithoutPublishing() {
        WorkflowDefinitionEntity existing = new WorkflowDefinitionEntity();
        existing.setId("workflow-1");
        existing.setKeySlug("order-service-order_assistant");
        existing.setName("Old");
        existing.setRuntimeType("LANGGRAPH4J");
        when(workflowDefinitionService.findByKeySlug("order-service-order_assistant")).thenReturn(Optional.of(existing));
        when(workflowDefinitionService.update(org.mockito.ArgumentMatchers.anyString(), any())).thenAnswer(invocation -> {
            WorkflowDefinitionEntity update = invocation.getArgument(1);
            update.setId("workflow-1");
            return update;
        });

        AgentGraphSyncResponse response = service.syncAgentGraphs("order-service",
                new AgentGraphSyncRequest("sync-2", "SDK", true, List.of(registration())));

        assertEquals(0, response.created());
        assertEquals(1, response.updated());
        verify(workflowDefinitionService).update(any(), any(WorkflowDefinitionEntity.class));
        verify(agentDefinitionService, never()).update(any(), any());
    }

    @Test
    void upsertPayloadIncludesGraphSpecCanvasAndSdkMetadata() {
        when(workflowDefinitionService.findByKeySlug("order-service-order_assistant")).thenReturn(Optional.empty());
        when(workflowDefinitionService.create(any())).thenAnswer(invocation -> invocation.getArgument(0));

        service.syncAgentGraphs("order-service",
                new AgentGraphSyncRequest("sync-3", "SDK", true, List.of(registration())));

        verify(workflowDefinitionService).create(org.mockito.ArgumentMatchers.argThat(def -> {
            assertEquals("LANGGRAPH4J", def.getRuntimeType());
            assertEquals("llm-1", def.getDefaultModelInstanceId());
            assertEquals("order-service-order_assistant", def.getKeySlug());
            assertNotNull(def.getGraphSpecJson());
            assertTrue(def.getCanvasJson().contains("\"type\":\"llm\""));
            assertTrue(def.getCanvasJson().contains("\"x\":520"));
            assertTrue(def.getCanvasJson().contains("\"y\":260"));
            assertTrue(def.getCanvasJson().contains("\"animated\":true"));
            assertTrue(def.getExtraJson().contains("\"managedBy\":\"SDK\""));
            assertTrue(def.getExtraJson().contains("\"overwriteMode\":\"DRAFT_ONLY\""));
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
