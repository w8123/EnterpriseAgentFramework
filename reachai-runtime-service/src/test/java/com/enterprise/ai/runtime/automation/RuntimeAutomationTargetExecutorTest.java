package com.enterprise.ai.runtime.automation;

import com.enterprise.ai.runtime.agent.RuntimeAgentConfigVersionMapper;
import com.enterprise.ai.runtime.agent.RuntimeAgentMapper;
import com.enterprise.ai.runtime.agent.RuntimeAgentEntity;
import com.enterprise.ai.runtime.agent.RuntimeAgentConfigVersionEntity;
import com.enterprise.ai.runtime.agent.RuntimeAgentPublishedConfigReader;
import com.enterprise.ai.runtime.client.capability.RuntimeCapabilityCatalogClient;
import com.enterprise.ai.runtime.client.control.RuntimeControlCatalogClient;
import com.enterprise.ai.runtime.client.model.RuntimeModelServiceClient;
import com.enterprise.ai.runtime.supervisor.RuntimeAgentExecutionService;
import com.enterprise.ai.runtime.execution.RuntimeGraphSpecExecutor;
import com.enterprise.ai.runtime.runops.RuntimeRunLifecycleService;
import com.enterprise.ai.runtime.runops.RuntimeRunSnapshots;
import com.enterprise.ai.runtime.trace.RuntimeTraceSpanMapper;
import com.enterprise.ai.runtime.workflow.RuntimeWorkflowDefinitionEntity;
import com.enterprise.ai.runtime.workflow.RuntimeWorkflowDefinitionMapper;
import com.enterprise.ai.runtime.workflow.RuntimeWorkflowVersionEntity;
import com.enterprise.ai.runtime.workflow.RuntimeWorkflowVersionMapper;
import com.enterprise.ai.runtime.workflow.RuntimeWorkflowExecutionReader;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import java.util.Map;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class RuntimeAutomationTargetExecutorTest {
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private final RuntimeModelServiceClient model = mock(RuntimeModelServiceClient.class);
    private final RuntimeRunLifecycleService runLifecycle = mock(RuntimeRunLifecycleService.class);
    private final RuntimeAgentMapper agents = mock(RuntimeAgentMapper.class);
    private final RuntimeAgentConfigVersionMapper agentVersions = mock(RuntimeAgentConfigVersionMapper.class);
    private final RuntimeAgentExecutionService agentExecution = mock(RuntimeAgentExecutionService.class);
    private final RuntimeWorkflowVersionEntity published = new RuntimeWorkflowVersionEntity();
    private final RuntimeWorkflowDefinitionEntity workflow = new RuntimeWorkflowDefinitionEntity();
    private final RuntimeAutomationEntity automation = new RuntimeAutomationEntity();
    private final RuntimeAutomationVersionEntity target = new RuntimeAutomationVersionEntity();
    private RuntimeAutomationTargetExecutor executor;

    @BeforeEach
    void setUp() {
        var workflows = mock(RuntimeWorkflowDefinitionMapper.class);
        var releases = mock(RuntimeWorkflowVersionMapper.class);
        workflow.setId("wf-orders");
        workflow.setStatus("ACTIVE");
        workflow.setName("Orders");
        workflow.setProjectId(8L);
        workflow.setProjectCode("orders");
        when(workflows.selectBatchIds(any())).thenReturn(List.of(workflow));
        published.setId(44L);
        published.setWorkflowId("wf-orders");
        published.setVersion("1.0.0");
        published.setStatus("ACTIVE");
        published.setGraphSpecSnapshotJson("""
                {"entryNodeId":"llm","exitNodeIds":["llm"],
                 "nodes":[{"id":"llm","type":"LLM","config":{"userPrompt":"hello"}}]}
                """);
        published.setSnapshotJson("{\"projectId\":8,\"projectCode\":\"orders\",\"defaultModelInstanceId\":\"published-model\"}");
        when(releases.selectBatchIds(any())).thenReturn(List.of(published));
        automation.setTenantId("tenant-a");
        automation.setProjectId(8L);
        automation.setProjectCode("orders");
        target.setTargetType("WORKFLOW");
        target.setTargetId("wf-orders");
        target.setTargetVersionId(44L);
        target.setPrincipalId("automation-a");
        var data = new RuntimeModelServiceClient.ModelChatData();
        data.setContent("answer");
        var response = new RuntimeModelServiceClient.ModelChatResult();
        response.setCode(200);
        response.setData(data);
        when(model.chat(any())).thenReturn(response);
        var json = new ObjectMapper();
        var engine = new RuntimeGraphSpecExecutor(json, model,
                mock(RuntimeCapabilityCatalogClient.class), mock(RuntimeControlCatalogClient.class));
        executor = new RuntimeAutomationTargetExecutor(
                new RuntimeAgentPublishedConfigReader(agents, agentVersions),
                agentExecution,
                new RuntimeWorkflowExecutionReader(workflows, releases),
                engine,
                runLifecycle,
                new com.enterprise.ai.runtime.trace.RuntimeTraceRootService(mock(RuntimeTraceSpanMapper.class), new com.fasterxml.jackson.databind.ObjectMapper()),
                mock(RuntimeAutomationInteractionTerminationService.class),
                new RuntimeAutomationJsonSupport(json),
                scheduler);
    }

    @AfterEach
    void tearDown() { scheduler.shutdownNow(); }

    @Test
    void realEngineUsesPublishedDefaultEvenWhenScheduledInputContainsModelOverrides() {
        var result = run(Map.of("modelInstanceId", "caller-model", "workflowDefaultModelInstanceId", "forged"));
        assertTrue(result.success(), result.code());
        var sent = ArgumentCaptor.forClass(RuntimeModelServiceClient.ModelChatRequest.class);
        verify(model).chat(sent.capture());
        assertEquals("published-model", sent.getValue().getModelInstanceId());
        var recorded = ArgumentCaptor.forClass(RuntimeRunSnapshots.PublishedWorkflow.class);
        verify(runLifecycle).beginPublishedWorkflow(eq("trace-a"), anyString(), eq("AUTOMATION"),
                recorded.capture(), anyMap(), any());
        String pinnedGraph = published.getGraphSpecSnapshotJson();
        published.setGraphSpecSnapshotJson("later draft graph");
        assertEquals(44L, recorded.getValue().versionId());
        assertEquals("1.0.0", recorded.getValue().version());
        assertEquals("orders", recorded.getValue().projectCode());
        assertEquals(pinnedGraph, recorded.getValue().graphSpecJson());
    }

    @Test
    void explicitNullDefaultCannotFallBackToScheduledInput() {
        published.setSnapshotJson("{\"defaultModelInstanceId\":null}");
        var result = run(Map.of("modelInstanceId", "caller-model"));
        assertFalse(result.success());
        assertEquals("RUNTIME_GRAPH_MODEL_REQUIRED", result.code());
        verify(model, never()).chat(any());
    }

    @Test
    void invalidSnapshotStopsBeforeOutboundModelCallAndIsNotRetried() {
        published.setSnapshotJson("{}");
        var result = run(Map.of());
        assertEquals("AUTOMATION_WORKFLOW_SNAPSHOT_INVALID", result.code());
        assertFalse(result.retryable());
        verify(model, never()).chat(any());
    }

    @Test
    void disabledWorkflowStopsBeforeThePinnedVersionCanExecute() {
        workflow.setStatus("DISABLED");

        var result = run(Map.of());

        assertFalse(result.success());
        assertEquals("AUTOMATION_TARGET_VERSION_UNAVAILABLE", result.code());
        assertFalse(result.retryable());
        verifyNoInteractions(model, runLifecycle);
    }

    @Test
    void retiredWorkflowVersionRemainsExecutableWhileItsOwnerIsActive() {
        published.setStatus("RETIRED");

        assertTrue(run(Map.of()).success());
        verify(model).chat(any());
    }

    @Test
    void archivedAgentConfigKeepsItsPinnedIdentityInsteadOfFollowingTheCurrentPointer() {
        agentTarget(true, "ARCHIVED");

        assertTrue(run(Map.of()).success());
        verify(agentExecution).executePublishedConfig(eq("agent-a"), eq(11L), anyMap(), eq(true), any(), any(), any());
        verify(agentVersions, never()).selectById(99L);
    }

    @Test
    void disabledAgentStopsBeforeEnteringTheExecutionService() {
        agentTarget(false, "ACTIVE");

        var result = run(Map.of());

        assertEquals("AUTOMATION_TARGET_VERSION_UNAVAILABLE", result.code());
        assertFalse(result.retryable());
        verifyNoInteractions(agentExecution, model);
    }

    private void agentTarget(boolean enabled, String status) {
        var agent = new RuntimeAgentEntity();
        agent.setId("agent-a");
        agent.setEnabled(enabled);
        agent.setActiveConfigVersionId(99L);
        var config = new RuntimeAgentConfigVersionEntity();
        config.setId(11L);
        config.setAgentId("agent-a");
        config.setStatus(status);
        when(agents.selectById("agent-a")).thenReturn(agent);
        when(agentVersions.selectById(11L)).thenReturn(config);
        when(agentExecution.executePublishedConfig(eq("agent-a"), eq(11L), anyMap(), eq(true), any(), any(), any()))
                .thenReturn(Map.of("success", true, "answer", "done"));
        target.setTargetType("AGENT");
        target.setTargetId("agent-a");
        target.setTargetVersionId(11L);
    }

    private RuntimeAutomationTargetExecutor.ExecutionOutcome run(Map<String, Object> input) {
        return executor.execute(automation, target, new RuntimeAutomationOccurrenceEntity(), "trace-a", input);
    }
}
