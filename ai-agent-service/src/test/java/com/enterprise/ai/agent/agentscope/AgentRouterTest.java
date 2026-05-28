package com.enterprise.ai.agent.agentscope;

import com.enterprise.ai.agent.agent.AgentDefinition;
import com.enterprise.ai.agent.agent.AgentDefinitionService;
import com.enterprise.ai.agent.model.AgentResult;
import com.enterprise.ai.agent.runtime.AgentRuntimeAdapter;
import com.enterprise.ai.agent.runtime.AgentRuntimeRequest;
import com.enterprise.ai.agent.runtime.AgentRuntimeResult;
import com.enterprise.ai.agent.runtime.AgentRuntimeSelector;
import com.enterprise.ai.agent.runtime.EmbeddedRuntimeDispatchRequest;
import com.enterprise.ai.agent.runtime.EmbeddedRuntimeDispatchResult;
import com.enterprise.ai.agent.runtime.EmbeddedRuntimeDispatchService;
import com.enterprise.ai.agent.governance.GuardDecisionLogService;
import com.enterprise.ai.agent.service.IntentService;
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
        IntentService intentService = mock(IntentService.class);
        AgentDefinitionService definitionService = mock(AgentDefinitionService.class);
        AgentRuntimeSelector selector = mock(AgentRuntimeSelector.class);
        AgentRuntimeAdapter adapter = mock(AgentRuntimeAdapter.class);
        EmbeddedRuntimeDispatchService dispatchService = mock(EmbeddedRuntimeDispatchService.class);
        GuardDecisionLogService guardDecisionLogService = mock(GuardDecisionLogService.class);
        AgentRouter router = new AgentRouter(intentService, definitionService, selector, dispatchService, guardDecisionLogService);

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

        AgentResult result = router.executeByDefinition(AgentDefinition.builder()
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
        IntentService intentService = mock(IntentService.class);
        AgentDefinitionService definitionService = mock(AgentDefinitionService.class);
        AgentRuntimeSelector selector = mock(AgentRuntimeSelector.class);
        EmbeddedRuntimeDispatchService dispatchService = mock(EmbeddedRuntimeDispatchService.class);
        GuardDecisionLogService guardDecisionLogService = mock(GuardDecisionLogService.class);
        AgentRouter router = new AgentRouter(intentService, definitionService, selector, dispatchService, guardDecisionLogService);

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

        AgentResult result = router.executeByDefinition(AgentDefinition.builder()
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
        verifyNoInteractions(selector);

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
        IntentService intentService = mock(IntentService.class);
        AgentDefinitionService definitionService = mock(AgentDefinitionService.class);
        AgentRuntimeSelector selector = mock(AgentRuntimeSelector.class);
        AgentRuntimeAdapter adapter = mock(AgentRuntimeAdapter.class);
        EmbeddedRuntimeDispatchService dispatchService = mock(EmbeddedRuntimeDispatchService.class);
        GuardDecisionLogService guardDecisionLogService = mock(GuardDecisionLogService.class);
        AgentRouter router = new AgentRouter(intentService, definitionService, selector, dispatchService, guardDecisionLogService);

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

        AgentResult result = router.executeByDefinition(AgentDefinition.builder()
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
        IntentService intentService = mock(IntentService.class);
        AgentDefinitionService definitionService = mock(AgentDefinitionService.class);
        AgentRuntimeSelector selector = mock(AgentRuntimeSelector.class);
        EmbeddedRuntimeDispatchService dispatchService = mock(EmbeddedRuntimeDispatchService.class);
        GuardDecisionLogService guardDecisionLogService = mock(GuardDecisionLogService.class);
        AgentRouter router = new AgentRouter(intentService, definitionService, selector, dispatchService, guardDecisionLogService);

        AgentResult result = router.executeByDefinition(AgentDefinition.builder()
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
        verifyNoInteractions(selector);
        verifyNoInteractions(dispatchService);
        verify(guardDecisionLogService).record(
                any(),
                org.mockito.ArgumentMatchers.eq("AGENT_RBAC"),
                org.mockito.ArgumentMatchers.eq("AGENT"),
                org.mockito.ArgumentMatchers.eq("restricted-agent"),
                org.mockito.ArgumentMatchers.eq("DENY"),
                org.mockito.ArgumentMatchers.eq("user role is not allowed"),
                any());
    }
}
