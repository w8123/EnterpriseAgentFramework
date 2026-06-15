package com.enterprise.ai.agent.workflow;

import com.enterprise.ai.agent.graph.GraphSpec;
import com.enterprise.ai.agent.identity.EmbedSessionEntity;
import com.enterprise.ai.agent.runtime.GraphRuntimeContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class EmbedWorkflowRuntimeServiceTest {

    @Test
    void resolvesPageWorkflowActiveVersionForGlobalEmbeddedAgent() throws Exception {
        AgentEntryService agentEntryService = mock(AgentEntryService.class);
        AgentWorkflowResolver resolver = mock(AgentWorkflowResolver.class);
        WorkflowDefinitionService workflowService = mock(WorkflowDefinitionService.class);
        WorkflowVersionService versionService = mock(WorkflowVersionService.class);
        ObjectMapper objectMapper = new ObjectMapper();
        EmbedWorkflowRuntimeService service = new EmbedWorkflowRuntimeService(
                agentEntryService,
                resolver,
                workflowService,
                versionService,
                new WorkflowAgentDefinitionAdapter(objectMapper));

        AgentEntryEntity entry = new AgentEntryEntity();
        entry.setId("agent-1");
        entry.setKeySlug("global-agent");
        entry.setName("Global Assistant");
        entry.setAgentKind("GLOBAL_EMBED");
        entry.setProjectCode("orders");
        entry.setSystemPrompt("You operate inside the current business page.");
        entry.setEnabled(true);
        when(agentEntryService.findById("global-agent")).thenReturn(Optional.empty());
        when(agentEntryService.findByKeySlug("global-agent")).thenReturn(Optional.of(entry));

        AgentWorkflowBindingEntity binding = new AgentWorkflowBindingEntity();
        binding.setWorkflowId("workflow-1");
        binding.setBindingType("PAGE");
        binding.setPageKey("orders.list");
        binding.setEnabled(true);
        when(resolver.resolve(argThat(request ->
                "agent-1".equals(request.agentId())
                        && "orders".equals(request.projectCode())
                        && "orders.list".equals(request.pageKey())
                        && "/orders".equals(request.route()))))
                .thenReturn(Optional.of(binding));

        WorkflowDefinitionEntity workflow = new WorkflowDefinitionEntity();
        workflow.setId("workflow-1");
        workflow.setKeySlug("orders-list-assistant");
        workflow.setName("Orders List Assistant");
        workflow.setRuntimeType("LANGGRAPH4J");
        workflow.setDefaultModelInstanceId("llm-1");
        workflow.setGraphSpecJson(objectMapper.writeValueAsString(GraphSpec.builder()
                .code("orders_list")
                .name("Orders List")
                .entry("answer")
                .finishNode("answer")
                .node(GraphSpec.Node.builder()
                        .id("answer")
                        .type("LLM")
                        .name("Answer")
                        .build())
                .build()));
        workflow.setCanvasJson("{\"nodes\":[]}");
        when(workflowService.findById("workflow-1")).thenReturn(Optional.of(workflow));

        WorkflowVersionEntity active = new WorkflowVersionEntity();
        active.setId(9L);
        active.setWorkflowId("workflow-1");
        active.setVersion("v1");
        active.setGraphSpecSnapshotJson(workflow.getGraphSpecJson());
        active.setCanvasSnapshotJson("{\"nodes\":[{\"id\":\"answer\"}]}");
        when(versionService.resolveActive("workflow-1")).thenReturn(active);

        EmbedSessionEntity session = new EmbedSessionEntity();
        session.setAgentId("global-agent");
        session.setProjectCode("orders");
        session.setPageKey("orders.list");
        session.setRoute("/orders");

        Optional<WorkflowAgentDefinitionAdapter.RuntimeGraph> resolved = service.resolveRunnableGraph(session, null);

        assertTrue(resolved.isPresent());
        WorkflowAgentDefinitionAdapter.RuntimeGraph runtimeGraph = resolved.get();
        GraphRuntimeContext context = runtimeGraph.runtimeContext();
        assertEquals("orders_list", runtimeGraph.graphSpec().getCode());
        assertEquals("workflow-1", context.getSourceId());
        assertEquals("orders-list-assistant", context.getSourceKeySlug());
        assertEquals("Orders List Assistant", context.getName());
        assertEquals("WORKFLOW_VERSION", context.getSourceType());
        assertEquals("LANGGRAPH4J", context.getRuntimeType());
        assertEquals("llm-1", context.getModelInstanceId());
        assertEquals("You operate inside the current business page.", context.getSystemPrompt());
        assertEquals("{\"nodes\":[{\"id\":\"answer\"}]}", context.getCanvasJson());
        assertEquals("workflow-1", context.getExtra().get("workflowId"));
        assertEquals("v1", context.getExtra().get("workflowVersion"));
        assertEquals("agent-1", context.getExtra().get("entryAgentId"));
    }
}
