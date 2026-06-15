package com.enterprise.ai.agent.workflow;

import com.enterprise.ai.agent.agent.AgentDefinition;
import com.enterprise.ai.agent.agentscope.AgentRouter;
import com.enterprise.ai.agent.graph.GraphSpec;
import com.enterprise.ai.agent.model.AgentResult;
import com.enterprise.ai.agent.runtime.GraphRuntimeContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WorkflowRuntimeServiceTest {

    @Test
    void executeUsesActiveWorkflowVersionAndPassesPageMetadata() throws Exception {
        AgentRouter agentRouter = mock(AgentRouter.class);
        WorkflowRuntimeService service = new WorkflowRuntimeService(agentRouter, new WorkflowAgentDefinitionAdapter(new ObjectMapper()));
        when(agentRouter.executeByGraphSpec(any(), any(), eq("session-1"), eq("user-1"), eq("hello"), eq(List.of("BUYER")), any()))
                .thenReturn(AgentResult.builder()
                        .success(true)
                        .answer("ok")
                        .metadata(Map.of("traceId", "trace-1"))
                        .build());

        AgentResult result = service.execute(WorkflowRuntimeRequest.builder()
                .traceId("trace-1")
                .sessionId("session-1")
                .message("hello")
                .agent(agent())
                .workflow(workflow("{\"code\":\"draft\",\"nodes\":[{\"id\":\"draft\",\"type\":\"ANSWER\"}],\"entry\":\"draft\"}"))
                .activeVersion(activeVersion("v1", "{\"code\":\"active\",\"nodes\":[{\"id\":\"answer\",\"type\":\"ANSWER\"}],\"entry\":\"answer\"}"))
                .principal(Map.of("externalUserId", "user-1", "roles", List.of("BUYER")))
                .pageContext(Map.of("pageKey", "orders.list", "route", "/orders"))
                .metadata(Map.of("bindingId", 7L))
                .build());

        assertTrue(result.isSuccess());
        ArgumentCaptor<GraphSpec> graphCaptor = ArgumentCaptor.forClass(GraphSpec.class);
        ArgumentCaptor<GraphRuntimeContext> contextCaptor = ArgumentCaptor.forClass(GraphRuntimeContext.class);
        ArgumentCaptor<Map<String, Object>> metadataCaptor = ArgumentCaptor.forClass(Map.class);
        verify(agentRouter).executeByGraphSpec(
                graphCaptor.capture(),
                contextCaptor.capture(),
                eq("session-1"),
                eq("user-1"),
                eq("hello"),
                eq(List.of("BUYER")),
                metadataCaptor.capture());
        assertEquals("active", graphCaptor.getValue().getCode());
        assertEquals("WORKFLOW_VERSION", contextCaptor.getValue().getSourceType());
        assertEquals("wf-1", contextCaptor.getValue().getSourceId());
        assertEquals("wf-1", metadataCaptor.getValue().get("resolvedWorkflowId"));
        assertEquals("orders", metadataCaptor.getValue().get("workflowKeySlug"));
        assertEquals(7L, metadataCaptor.getValue().get("bindingId"));
        assertEquals("orders.list", metadataCaptor.getValue().get("pageKey"));
        assertEquals("/orders", metadataCaptor.getValue().get("route"));
        assertEquals("v1", metadataCaptor.getValue().get("workflowVersion"));
        assertEquals("WORKFLOW_VERSION", metadataCaptor.getValue().get("sourceType"));
        assertEquals("wf-1", metadataCaptor.getValue().get("sourceId"));
        assertEquals("agent-1", metadataCaptor.getValue().get("entryAgentId"));
        assertEquals("global-agent", metadataCaptor.getValue().get("entryAgentKeySlug"));
    }

    @Test
    void executeRequiresActiveVersionUnlessDraftFallbackIsAllowed() {
        WorkflowRuntimeService service = new WorkflowRuntimeService(mock(AgentRouter.class), new WorkflowAgentDefinitionAdapter(new ObjectMapper()));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.execute(WorkflowRuntimeRequest.builder()
                        .sessionId("session-1")
                        .message("hello")
                        .agent(agent())
                        .workflow(workflow("{\"nodes\":[{\"id\":\"answer\",\"type\":\"ANSWER\"}],\"entry\":\"answer\"}"))
                        .build()));

        assertEquals("active workflow version is required", error.getMessage());
    }

    @Test
    void executeCanUseDraftGraphWhenAllowedForDebug() throws Exception {
        AgentRouter agentRouter = mock(AgentRouter.class);
        WorkflowRuntimeService service = new WorkflowRuntimeService(agentRouter, new WorkflowAgentDefinitionAdapter(new ObjectMapper()));
        when(agentRouter.executeByGraphSpec(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(AgentResult.builder().success(true).answer("debug").build());

        service.execute(WorkflowRuntimeRequest.builder()
                .sessionId("debug-session")
                .message("debug")
                .agent(agent())
                .workflow(workflow("{\"code\":\"draft\",\"nodes\":[{\"id\":\"answer\",\"type\":\"ANSWER\"}],\"entry\":\"answer\"}"))
                .allowDraftFallback(true)
                .build());

        ArgumentCaptor<GraphSpec> graphCaptor = ArgumentCaptor.forClass(GraphSpec.class);
        ArgumentCaptor<GraphRuntimeContext> contextCaptor = ArgumentCaptor.forClass(GraphRuntimeContext.class);
        verify(agentRouter).executeByGraphSpec(graphCaptor.capture(), contextCaptor.capture(), any(), any(), any(), any(), any());
        assertEquals("draft", graphCaptor.getValue().getCode());
        assertEquals("WORKFLOW_DRAFT", contextCaptor.getValue().getSourceType());
    }

    @Test
    void executionShellKeepsAgentEntryAndWorkflowBoundaries() throws Exception {
        WorkflowRuntimeService service = new WorkflowRuntimeService(mock(AgentRouter.class), new WorkflowAgentDefinitionAdapter(new ObjectMapper()));

        AgentDefinition shell = service.toExecutionShell(
                agent(),
                workflow("{\"code\":\"active\",\"nodes\":[{\"id\":\"answer\",\"type\":\"ANSWER\"}],\"entry\":\"answer\"}"),
                activeVersion("v1", "{\"code\":\"active\",\"nodes\":[{\"id\":\"answer\",\"type\":\"ANSWER\"}],\"entry\":\"answer\"}"),
                Map.of("bindingId", 7L));

        assertEquals("wf-1", shell.getId());
        assertEquals("orders", shell.getKeySlug());
        assertEquals("WORKFLOW", shell.getAgentMode());
        assertEquals("LANGGRAPH4J", shell.getRuntimeType());
        assertEquals("llm-1", shell.getModelInstanceId());
        assertEquals("agent-1", shell.getExtra().get("entryAgentId"));
        assertEquals("wf-1", shell.getExtra().get("workflowId"));
        assertEquals(7L, shell.getExtra().get("bindingId"));
    }

    @Test
    void runtimeGraphExposesGraphSpecNativeBundle() throws Exception {
        WorkflowRuntimeService service = new WorkflowRuntimeService(mock(AgentRouter.class), new WorkflowAgentDefinitionAdapter(new ObjectMapper()));
        WorkflowAgentDefinitionAdapter.RuntimeGraph runtimeGraph = service.toRuntimeGraph(
                agent(),
                workflow("{\"code\":\"active\",\"nodes\":[{\"id\":\"answer\",\"type\":\"ANSWER\"}],\"entry\":\"answer\"}"),
                activeVersion("v1", "{\"code\":\"active\",\"nodes\":[{\"id\":\"answer\",\"type\":\"ANSWER\"}],\"entry\":\"answer\"}"),
                Map.of("bindingId", 7L));

        assertEquals("active", runtimeGraph.graphSpec().getCode());
        assertEquals("WORKFLOW_VERSION", runtimeGraph.runtimeContext().getSourceType());
        assertEquals("wf-1", runtimeGraph.runtimeContext().getSourceId());
        assertEquals("agent-1", runtimeGraph.runtimeContext().getExtra().get("entryAgentId"));
    }

    private AgentEntryEntity agent() {
        AgentEntryEntity agent = new AgentEntryEntity();
        agent.setId("agent-1");
        agent.setKeySlug("global-agent");
        agent.setName("Global Agent");
        agent.setProjectCode("demo");
        agent.setSystemPrompt("You operate inside the current page.");
        agent.setModelInstanceId("entry-model");
        agent.setVisibility("PROJECT");
        return agent;
    }

    private WorkflowDefinitionEntity workflow(String graphSpecJson) {
        WorkflowDefinitionEntity workflow = new WorkflowDefinitionEntity();
        workflow.setId("wf-1");
        workflow.setKeySlug("orders");
        workflow.setName("Orders Workflow");
        workflow.setProjectCode("demo");
        workflow.setWorkflowType("PAGE_ACTION");
        workflow.setRuntimeType("LANGGRAPH4J");
        workflow.setDefaultModelInstanceId("llm-1");
        workflow.setGraphSpecJson(graphSpecJson);
        workflow.setCanvasJson("{\"nodes\":[]}");
        return workflow;
    }

    private WorkflowVersionEntity activeVersion(String version, String graphSpecJson) throws Exception {
        WorkflowVersionEntity active = new WorkflowVersionEntity();
        active.setId(9L);
        active.setWorkflowId("wf-1");
        active.setVersion(version);
        active.setGraphSpecSnapshotJson(graphSpecJson);
        active.setCanvasSnapshotJson(new ObjectMapper().writeValueAsString(GraphSpec.builder().build()));
        return active;
    }
}
