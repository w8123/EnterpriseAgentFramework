package com.enterprise.ai.agent.agentscope;

import com.enterprise.ai.agent.graph.GraphSpec;
import com.enterprise.ai.agent.model.AgentResult;
import com.enterprise.ai.agent.runtime.AgentRuntimeAdapter;
import com.enterprise.ai.agent.runtime.AgentRuntimeProfile;
import com.enterprise.ai.agent.runtime.AgentRuntimeRequest;
import com.enterprise.ai.agent.runtime.AgentRuntimeResult;
import com.enterprise.ai.agent.runtime.AgentRuntimeSelector;
import com.enterprise.ai.agent.runtime.EmbeddedRuntimeDispatchRequest;
import com.enterprise.ai.agent.runtime.EmbeddedRuntimeDispatchResult;
import com.enterprise.ai.agent.runtime.EmbeddedRuntimeDispatchService;
import com.enterprise.ai.agent.runtime.GraphRuntimeContext;
import com.enterprise.ai.agent.governance.GuardDecisionLogService;
import com.enterprise.ai.agent.service.IntentService;
import com.enterprise.ai.agent.workflow.AgentEntryService;
import com.enterprise.ai.agent.workflow.AgentWorkflowBindingService;
import com.enterprise.ai.agent.workflow.WorkflowDefinitionService;
import com.enterprise.ai.agent.workflow.WorkflowRuntimeGraphAdapter;
import com.enterprise.ai.agent.workflow.WorkflowVersionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AgentRouterTest {

    @Test
    void mapsRuntimeStepsToAgentResultAndKeepsMetadataSteps() {
        AgentRuntimeSelector selector = mock(AgentRuntimeSelector.class);
        AgentRuntimeAdapter adapter = mock(AgentRuntimeAdapter.class);
        AgentRouter router = router(selector, mock(EmbeddedRuntimeDispatchService.class), mock(GuardDecisionLogService.class));

        when(selector.select(any(AgentRuntimeRequest.class))).thenReturn(adapter);
        when(adapter.execute(any(AgentRuntimeRequest.class))).thenReturn(AgentRuntimeResult.builder()
                .success(true)
                .answer("done")
                .runtimeType(AgentRuntimeAdapter.DEFAULT_RUNTIME_TYPE)
                .traceId("trace-1")
                .agentName("Demo Agent")
                .steps(List.of("Intent: GENERAL_CHAT", "Runtime: AGENTSCOPE"))
                .metadata(Map.of("elapsedMs", 12L))
                .build());

        AgentResult result = router.executeByProfile(AgentRuntimeProfile.builder()
                        .name("Demo Agent")
                        .keySlug("demo-agent")
                        .intentType("GENERAL_CHAT")
                        .runtimeType(AgentRuntimeAdapter.DEFAULT_RUNTIME_TYPE)
                        .build(),
                "session-1",
                "user-1",
                "hello");

        assertEquals("done", result.getAnswer());
        assertEquals(2, result.getSteps().size());
        assertEquals("Step 1", result.getSteps().get(0).getName());
        assertEquals("Intent: GENERAL_CHAT", result.getSteps().get(0).getDetail());
        assertEquals("Runtime: AGENTSCOPE", result.getSteps().get(1).getDetail());

        assertNotNull(result.getMetadata());
        assertEquals("trace-1", result.getMetadata().get("traceId"));
        assertEquals(AgentRuntimeAdapter.DEFAULT_RUNTIME_TYPE, result.getMetadata().get("runtimeType"));
        assertEquals("Demo Agent", result.getMetadata().get("agentName"));
        assertEquals(List.of("Intent: GENERAL_CHAT", "Runtime: AGENTSCOPE"), result.getMetadata().get("steps"));
    }

    @Test
    void dispatchesEmbeddedPlacementToManagedRuntimeInstance() {
        EmbeddedRuntimeDispatchService dispatchService = mock(EmbeddedRuntimeDispatchService.class);
        AgentRouter router = router(mock(AgentRuntimeSelector.class), dispatchService, mock(GuardDecisionLogService.class));

        when(dispatchService.dispatch(any(EmbeddedRuntimeDispatchRequest.class))).thenReturn(new EmbeddedRuntimeDispatchResult(
                true,
                "embedded done",
                "crm",
                "inst-1",
                "http://crm/eaf/runtime/embedded/execute",
                List.of("Remote runtime accepted", "Remote runtime completed"),
                Map.of("remote", true),
                null,
                null
        ));

        AgentResult result = router.executeByProfile(AgentRuntimeProfile.builder()
                        .name("Demo Agent")
                        .keySlug("demo-agent")
                        .intentType("GENERAL_CHAT")
                        .runtimeType(AgentRuntimeAdapter.DEFAULT_RUNTIME_TYPE)
                        .runtimePlacement("EMBEDDED")
                        .runtimeConfig(Map.of(
                                "embeddedRuntime", Map.of(
                                        "projectCode", "crm",
                                        "instanceId", "inst-1"
                                )
                        ))
                        .build(),
                "session-1",
                "user-1",
                "hello",
                List.of("admin"));

        assertEquals(true, result.isSuccess());
        assertEquals("embedded done", result.getAnswer());
        assertEquals(2, result.getSteps().size());
        assertEquals("EMBEDDED", result.getMetadata().get("runtimePlacement"));
        assertEquals("crm", result.getMetadata().get("projectCode"));
        assertEquals("inst-1", result.getMetadata().get("instanceId"));
        assertEquals(true, result.getMetadata().get("remote"));
        verifyNoInteractions(mock(AgentRuntimeSelector.class));

        ArgumentCaptor<EmbeddedRuntimeDispatchRequest> captor =
                ArgumentCaptor.forClass(EmbeddedRuntimeDispatchRequest.class);
        verify(dispatchService).dispatch(captor.capture());
        EmbeddedRuntimeDispatchRequest dispatchRequest = captor.getValue();
        assertEquals("crm", dispatchRequest.projectCode());
        assertEquals("inst-1", dispatchRequest.instanceId());
        assertEquals("demo-agent", dispatchRequest.agentKey());
        assertEquals("hello", dispatchRequest.message());
        assertEquals("session-1", dispatchRequest.sessionId());
        assertEquals("user-1", dispatchRequest.userId());
        assertEquals("GENERAL_CHAT", dispatchRequest.context().get("intentType"));
        assertEquals(List.of("admin"), dispatchRequest.context().get("roles"));
    }

    @Test
    void fallsBackToCentralRuntimeForHybridWhenEmbeddedFails() {
        AgentRuntimeSelector selector = mock(AgentRuntimeSelector.class);
        AgentRuntimeAdapter adapter = mock(AgentRuntimeAdapter.class);
        EmbeddedRuntimeDispatchService dispatchService = mock(EmbeddedRuntimeDispatchService.class);
        AgentRouter router = router(selector, dispatchService, mock(GuardDecisionLogService.class));

        when(dispatchService.dispatch(any(EmbeddedRuntimeDispatchRequest.class)))
                .thenThrow(new IllegalStateException("instance offline"));
        when(selector.select(any(AgentRuntimeRequest.class))).thenReturn(adapter);
        when(adapter.execute(any(AgentRuntimeRequest.class))).thenReturn(AgentRuntimeResult.builder()
                .success(true)
                .answer("central done")
                .runtimeType(AgentRuntimeAdapter.DEFAULT_RUNTIME_TYPE)
                .traceId("trace-2")
                .steps(List.of("Runtime: AGENTSCOPE"))
                .metadata(Map.of("central", true))
                .build());

        AgentResult result = router.executeByProfile(AgentRuntimeProfile.builder()
                        .name("Demo Agent")
                        .keySlug("demo-agent")
                        .intentType("GENERAL_CHAT")
                        .runtimeType(AgentRuntimeAdapter.DEFAULT_RUNTIME_TYPE)
                        .runtimePlacement("HYBRID")
                        .runtimeConfig(Map.of(
                                "embeddedRuntime", Map.of(
                                        "projectCode", "crm",
                                        "instanceId", "inst-1"
                                )
                        ))
                        .build(),
                "session-1",
                "user-1",
                "hello");

        assertEquals(true, result.isSuccess());
        assertEquals("central done", result.getAnswer());
        assertEquals(AgentRuntimeAdapter.DEFAULT_RUNTIME_TYPE, result.getMetadata().get("runtimeType"));
        assertEquals(true, result.getMetadata().get("central"));
        verify(dispatchService).dispatch(any(EmbeddedRuntimeDispatchRequest.class));
        verify(selector).select(any(AgentRuntimeRequest.class));
    }

    @Test
    void deniesAgentExecutionWhenUserRoleIsNotAllowed() {
        GuardDecisionLogService guardDecisionLogService = mock(GuardDecisionLogService.class);
        AgentRouter router = router(mock(AgentRuntimeSelector.class), mock(EmbeddedRuntimeDispatchService.class), guardDecisionLogService);

        AgentResult result = router.executeByProfile(AgentRuntimeProfile.builder()
                        .id("agent-1")
                        .name("Restricted Agent")
                        .keySlug("restricted-agent")
                        .intentType("GENERAL_CHAT")
                        .allowedRoles(List.of("admin"))
                        .build(),
                "session-1",
                "user-1",
                "hello",
                List.of("auditor"));

        assertEquals(false, result.isSuccess());
        assertEquals("Agent execution denied: user role is not allowed", result.getAnswer());
        assertEquals("AGENT_RBAC", result.getMetadata().get("decisionType"));
        verifyNoInteractions(mock(AgentRuntimeSelector.class));
        verifyNoInteractions(mock(EmbeddedRuntimeDispatchService.class));
        verify(guardDecisionLogService).record(
                any(),
                org.mockito.ArgumentMatchers.eq("AGENT_RBAC"),
                org.mockito.ArgumentMatchers.eq("AGENT"),
                org.mockito.ArgumentMatchers.eq("restricted-agent"),
                org.mockito.ArgumentMatchers.eq("DENY"),
                org.mockito.ArgumentMatchers.eq("user role is not allowed"),
                any());
    }

    @Test
    void executeByGraphSpecBuildsGraphNativeRuntimeRequest() {
        AgentRuntimeSelector selector = mock(AgentRuntimeSelector.class);
        AgentRuntimeAdapter adapter = mock(AgentRuntimeAdapter.class);
        AgentRouter router = router(selector, mock(EmbeddedRuntimeDispatchService.class), mock(GuardDecisionLogService.class));

        when(selector.select(any(AgentRuntimeRequest.class))).thenReturn(adapter);
        when(adapter.execute(any(AgentRuntimeRequest.class))).thenReturn(AgentRuntimeResult.builder()
                .success(true)
                .answer("workflow done")
                .runtimeType(AgentRuntimeAdapter.LANGGRAPH4J_RUNTIME_TYPE)
                .traceId("trace-wf")
                .agentName("Orders Workflow")
                .build());

        GraphSpec graphSpec = GraphSpec.builder()
                .code("orders")
                .entry("answer")
                .node(GraphSpec.Node.builder().id("answer").type("ANSWER").build())
                .build();
        GraphRuntimeContext runtimeContext = GraphRuntimeContext.builder()
                .sourceType("WORKFLOW_VERSION")
                .sourceId("wf-1")
                .sourceKeySlug("orders")
                .name("Orders Workflow")
                .intentType("PAGE_ACTION")
                .runtimeType(AgentRuntimeAdapter.LANGGRAPH4J_RUNTIME_TYPE)
                .extra(Map.of(
                        "workflowId", "wf-1",
                        "workflowKeySlug", "orders",
                        "workflowVersion", "v1",
                        "workflowVersionId", 9L,
                        "entryAgentId", "agent-1",
                        "entryAgentKeySlug", "global-agent"))
                .build();

        AgentResult result = router.executeByGraphSpec(
                graphSpec,
                runtimeContext,
                "session-1",
                "user-1",
                "hello",
                List.of("BUYER"),
                Map.of("traceId", "trace-wf", "workflowId", "wf-1"));

        assertEquals("workflow done", result.getAnswer());
        ArgumentCaptor<AgentRuntimeRequest> requestCaptor = ArgumentCaptor.forClass(AgentRuntimeRequest.class);
        verify(adapter).execute(requestCaptor.capture());
        AgentRuntimeRequest captured = requestCaptor.getValue();
        assertEquals("orders", captured.getGraphSpec().getCode());
        assertEquals("wf-1", captured.getGraphRuntimeContext().getSourceId());
        assertEquals("WORKFLOW_VERSION", captured.getGraphRuntimeContext().getSourceType());
        assertEquals("trace-wf", captured.getTraceId());
        assertEquals("wf-1", result.getMetadata().get("workflowId"));
        assertEquals(9L, result.getMetadata().get("workflowVersionId"));
        assertEquals("agent-1", result.getMetadata().get("entryAgentId"));
        assertEquals("global-agent", result.getMetadata().get("entryAgentKeySlug"));
        assertEquals("WORKFLOW_VERSION", result.getMetadata().get("sourceType"));
        assertEquals("wf-1", result.getMetadata().get("sourceId"));
    }

    private AgentRouter router(AgentRuntimeSelector selector,
                               EmbeddedRuntimeDispatchService dispatchService,
                               GuardDecisionLogService guardDecisionLogService) {
        return new AgentRouter(
                mock(IntentService.class),
                mock(AgentEntryService.class),
                mock(AgentWorkflowBindingService.class),
                mock(WorkflowDefinitionService.class),
                mock(WorkflowVersionService.class),
                mock(WorkflowRuntimeGraphAdapter.class),
                new ObjectMapper(),
                selector,
                dispatchService,
                guardDecisionLogService);
    }
}
