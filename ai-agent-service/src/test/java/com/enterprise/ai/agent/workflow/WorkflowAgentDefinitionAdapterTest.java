package com.enterprise.ai.agent.workflow;

import com.enterprise.ai.agent.agent.AgentDefinition;
import com.enterprise.ai.agent.graph.GraphSpec;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkflowAgentDefinitionAdapterTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final WorkflowAgentDefinitionAdapter adapter = new WorkflowAgentDefinitionAdapter(objectMapper);

    @Test
    void toRuntimeShellPrefersActiveVersionGraphSpec() throws Exception {
        AgentDefinition shell = adapter.toRuntimeShell(
                entryAgent(),
                workflow("{\"code\":\"draft\",\"nodes\":[{\"id\":\"draft\",\"type\":\"ANSWER\"}],\"entry\":\"draft\"}"),
                activeVersion("v1", "{\"code\":\"active\",\"nodes\":[{\"id\":\"answer\",\"type\":\"ANSWER\"}],\"entry\":\"answer\"}"),
                WorkflowAgentDefinitionAdapter.RuntimeShellOptions.builder()
                        .binding(binding())
                        .build());

        assertEquals("wf-1", shell.getId());
        assertEquals("WORKFLOW", shell.getAgentMode());
        assertEquals("active", shell.getGraphSpec().getCode());
        assertEquals("agent-1", shell.getExtra().get("entryAgentId"));
        assertEquals("wf-1", shell.getExtra().get("workflowId"));
        assertEquals("v1", shell.getExtra().get("workflowVersion"));
        assertEquals(9L, shell.getExtra().get("workflowVersionId"));
        assertEquals(7L, shell.getExtra().get("bindingId"));
    }

    @Test
    void toDebugShellFromDraftSupportsWorkflowNativePayload() throws Exception {
        Map<String, Object> draft = new LinkedHashMap<>();
        draft.put("workflowId", "workflow-1");
        draft.put("workflowKeySlug", "orders");
        draft.put("workflowName", "Orders Workflow");
        draft.put("workflowType", "PAGE_ACTION");
        draft.put("runtimeType", "LANGGRAPH4J");
        draft.put("graphSpecJson", objectMapper.writeValueAsString(GraphSpec.builder()
                .code("orders")
                .name("Orders Workflow")
                .node(GraphSpec.Node.builder().id("start").type("LLM").build())
                .build()));

        AgentDefinition shell = adapter.toDebugShellFromDraft("WORKFLOW_DRAFT", draft);

        assertEquals("workflow-1", shell.getId());
        assertEquals("orders", shell.getKeySlug());
        assertEquals("Orders Workflow", shell.getName());
        assertEquals("WORKFLOW", shell.getAgentMode());
        assertEquals("orders", shell.getGraphSpec().getCode());
        assertTrue(Boolean.TRUE.equals(shell.getExtra().get("workflowDebug")));
    }

    @Test
    void toRuntimeGraphPrefersActiveVersionGraphSpec() throws Exception {
        WorkflowAgentDefinitionAdapter.RuntimeGraph runtimeGraph = adapter.toRuntimeGraph(
                entryAgent(),
                workflow("{\"code\":\"draft\",\"nodes\":[{\"id\":\"draft\",\"type\":\"ANSWER\"}],\"entry\":\"draft\"}"),
                activeVersion("v1", "{\"code\":\"active\",\"nodes\":[{\"id\":\"answer\",\"type\":\"ANSWER\"}],\"entry\":\"answer\"}"),
                WorkflowAgentDefinitionAdapter.RuntimeShellOptions.builder()
                        .binding(binding())
                        .build());

        assertEquals("active", runtimeGraph.graphSpec().getCode());
        assertEquals("WORKFLOW_VERSION", runtimeGraph.runtimeContext().getSourceType());
        assertEquals("wf-1", runtimeGraph.runtimeContext().getSourceId());
        assertEquals("agent-1", runtimeGraph.runtimeContext().getExtra().get("entryAgentId"));
        assertEquals("global-agent", runtimeGraph.runtimeContext().getExtra().get("entryAgentKeySlug"));
        assertEquals("v1", runtimeGraph.runtimeContext().getExtra().get("workflowVersion"));
    }

    private AgentEntryEntity entryAgent() {
        AgentEntryEntity agent = new AgentEntryEntity();
        agent.setId("agent-1");
        agent.setKeySlug("global-agent");
        agent.setName("Global Agent");
        agent.setProjectCode("demo");
        agent.setSystemPrompt("prompt");
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

    private WorkflowVersionEntity activeVersion(String version, String graphSpecJson) {
        WorkflowVersionEntity active = new WorkflowVersionEntity();
        active.setId(9L);
        active.setWorkflowId("wf-1");
        active.setVersion(version);
        active.setGraphSpecSnapshotJson(graphSpecJson);
        active.setCanvasSnapshotJson("{\"nodes\":[]}");
        return active;
    }

    private AgentWorkflowBindingEntity binding() {
        AgentWorkflowBindingEntity binding = new AgentWorkflowBindingEntity();
        binding.setId(7L);
        binding.setBindingType("PAGE");
        binding.setIntentType("PAGE_ACTION");
        return binding;
    }
}
