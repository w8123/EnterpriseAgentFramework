package com.enterprise.ai.agent.controller;

import com.enterprise.ai.agent.graph.GraphSpec;
import com.enterprise.ai.agent.runtime.GraphRuntimeContext;
import com.enterprise.ai.agent.runtime.LangGraph4jRuntimeAdapter;
import com.enterprise.ai.agent.workflow.WorkflowRuntimeGraphAdapter;
import com.enterprise.ai.agent.workflow.WorkflowDefinitionEntity;
import com.enterprise.ai.agent.workflow.WorkflowDefinitionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WorkflowStudioDebugControllerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void exposesWorkflowStudioDebugEndpoints() throws Exception {
        RequestMapping root = WorkflowStudioDebugController.class.getAnnotation(RequestMapping.class);
        assertArrayEquals(new String[]{"/api/workflows/studio"}, root.value());

        Method debugNode = WorkflowStudioDebugController.class.getMethod(
                "debugNode",
                WorkflowStudioDebugController.NodeDebugRequest.class);
        assertArrayEquals(new String[]{"/debug-node"}, debugNode.getAnnotation(PostMapping.class).value());

        Method debugRun = WorkflowStudioDebugController.class.getMethod(
                "debugRun",
                WorkflowStudioDebugController.DebugRunRequest.class);
        assertArrayEquals(new String[]{"/debug-run"}, debugRun.getAnnotation(PostMapping.class).value());
    }

    @Test
    void debugNodeUsesGraphSpecNativeAdapterEntry() throws Exception {
        LangGraph4jRuntimeAdapter adapter = mock(LangGraph4jRuntimeAdapter.class);
        WorkflowDefinitionService workflowService = mock(WorkflowDefinitionService.class);
        WorkflowStudioDebugController controller = new WorkflowStudioDebugController(
                adapter,
                workflowService,
                new WorkflowRuntimeGraphAdapter(objectMapper));
        String graphSpecJson = objectMapper.writeValueAsString(GraphSpec.builder()
                .code("orders")
                .name("Orders Workflow")
                .node(GraphSpec.Node.builder().id("start").type("LLM").name("Start").build())
                .build());
        WorkflowStudioDebugController.NodeDebugRequest request = new WorkflowStudioDebugController.NodeDebugRequest();
        request.setWorkflowId("workflow-1");
        request.setWorkflowName("Orders Workflow");
        request.setRuntimeType("LANGGRAPH4J");
        request.setGraphSpecJson(graphSpecJson);
        request.setNodeId("start");
        request.setMessage("hello");
        request.setState(Map.of("foo", "bar"));
        LangGraph4jRuntimeAdapter.NodeDebugResult result = LangGraph4jRuntimeAdapter.NodeDebugResult.builder()
                .nodeId("start")
                .success(true)
                .build();
        when(adapter.debugNode(org.mockito.ArgumentMatchers.any(GraphSpec.class),
                org.mockito.ArgumentMatchers.any(GraphRuntimeContext.class),
                eq("start"),
                eq("hello"),
                eq(Map.of("foo", "bar"))))
                .thenReturn(result);

        assertEquals(result, controller.debugNode(request).getBody());

        ArgumentCaptor<GraphSpec> graphCaptor = ArgumentCaptor.forClass(GraphSpec.class);
        ArgumentCaptor<GraphRuntimeContext> contextCaptor = ArgumentCaptor.forClass(GraphRuntimeContext.class);
        verify(adapter).debugNode(graphCaptor.capture(), contextCaptor.capture(), eq("start"), eq("hello"), eq(Map.of("foo", "bar")));
        assertEquals("orders", graphCaptor.getValue().getCode());
        GraphRuntimeContext context = contextCaptor.getValue();
        assertEquals("WORKFLOW_DRAFT", context.getSourceType());
        assertEquals("workflow-1", context.getSourceId());
        assertEquals("Orders Workflow", context.getName());
        assertEquals("LANGGRAPH4J", context.getRuntimeType());
        assertNotNull(context.getExtra());
        assertEquals("workflow-1", context.getExtra().get("workflowId"));
    }

    @Test
    void debugRunCanLoadWorkflowGraphWhenGraphSpecJsonIsMissing() throws Exception {
        LangGraph4jRuntimeAdapter adapter = mock(LangGraph4jRuntimeAdapter.class);
        WorkflowDefinitionService workflowService = mock(WorkflowDefinitionService.class);
        WorkflowStudioDebugController controller = new WorkflowStudioDebugController(
                adapter,
                workflowService,
                new WorkflowRuntimeGraphAdapter(objectMapper));
        WorkflowDefinitionEntity workflow = new WorkflowDefinitionEntity();
        workflow.setId("workflow-1");
        workflow.setKeySlug("orders-workflow");
        workflow.setName("Orders Workflow");
        workflow.setRuntimeType("LANGGRAPH4J");
        workflow.setGraphSpecJson(objectMapper.writeValueAsString(GraphSpec.builder()
                .code("orders")
                .node(GraphSpec.Node.builder().id("start").type("LLM").build())
                .build()));
        when(workflowService.findById("workflow-1")).thenReturn(Optional.of(workflow));
        WorkflowStudioDebugController.DebugRunRequest request = new WorkflowStudioDebugController.DebugRunRequest();
        request.setWorkflowId("workflow-1");
        request.setMessage("hello");
        request.setInputParams(Map.of("id", 1));
        request.setDebugOptions(Map.of("traceId", "trace-1"));
        LangGraph4jRuntimeAdapter.WorkflowDebugRunResult result = LangGraph4jRuntimeAdapter.WorkflowDebugRunResult.builder()
                .runId("run-1")
                .success(true)
                .build();
        when(adapter.debugRun(org.mockito.ArgumentMatchers.any(GraphSpec.class),
                org.mockito.ArgumentMatchers.any(GraphRuntimeContext.class),
                eq("hello"),
                eq(Map.of("id", 1)),
                eq(Map.of("traceId", "trace-1"))))
                .thenReturn(result);

        assertEquals(result, controller.debugRun(request).getBody());

        ArgumentCaptor<GraphRuntimeContext> contextCaptor = ArgumentCaptor.forClass(GraphRuntimeContext.class);
        verify(adapter).debugRun(org.mockito.ArgumentMatchers.any(GraphSpec.class),
                contextCaptor.capture(),
                eq("hello"),
                eq(Map.of("id", 1)),
                eq(Map.of("traceId", "trace-1")));
        assertEquals("orders-workflow", contextCaptor.getValue().getSourceKeySlug());
    }
}
