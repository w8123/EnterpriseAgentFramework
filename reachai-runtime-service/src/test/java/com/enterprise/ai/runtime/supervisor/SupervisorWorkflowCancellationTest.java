package com.enterprise.ai.runtime.supervisor;

import com.enterprise.ai.runtime.agent.RuntimeAgentConfigVersionEntity;
import com.enterprise.ai.runtime.agent.RuntimeAgentView;
import com.enterprise.ai.runtime.agent.RuntimeAgentWorkflowToolEntity;
import com.enterprise.ai.runtime.chat.RuntimeChatMemoryStore;
import com.enterprise.ai.runtime.client.model.RuntimeModelServiceClient;
import com.enterprise.ai.runtime.client.model.RuntimeModelServiceClient.ModelChatData;
import com.enterprise.ai.runtime.client.model.RuntimeModelServiceClient.ModelChatResult;
import com.enterprise.ai.runtime.client.model.RuntimeModelServiceClient.ModelUsage;
import com.enterprise.ai.runtime.execution.RuntimeAgentExecutionCancellation;
import com.enterprise.ai.runtime.execution.RuntimeGraphSpecExecutionCancellation;
import com.enterprise.ai.runtime.execution.RuntimeGraphSpecExecutionResult;
import com.enterprise.ai.runtime.execution.RuntimeGraphSpecExecutor;
import com.enterprise.ai.runtime.workflow.RuntimeWorkflowDefinitionEntity;
import com.enterprise.ai.runtime.workflow.RuntimeWorkflowDefinitionMapper;
import com.enterprise.ai.runtime.workflow.RuntimeWorkflowVersionEntity;
import com.enterprise.ai.runtime.workflow.RuntimeWorkflowVersionMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Workflow-as-Tool 必须接入请求级取消；取消不得伪装为 TIMEOUT。
 */
class SupervisorWorkflowCancellationTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final RuntimeWorkflowDefinitionMapper workflowMapper = mock(RuntimeWorkflowDefinitionMapper.class);
    private final RuntimeWorkflowVersionMapper versionMapper = mock(RuntimeWorkflowVersionMapper.class);
    private final RuntimeGraphSpecExecutor graphExecutor = mock(RuntimeGraphSpecExecutor.class);
    private final SupervisorExecutionTraceService traceService = mock(SupervisorExecutionTraceService.class);
    private final Map<String, RuntimeWorkflowDefinitionEntity> workflowTargets = new LinkedHashMap<>();
    private final Map<Long, RuntimeWorkflowVersionEntity> workflowVersions = new LinkedHashMap<>();

    @Test
    void cancelBeforeWorkflowDoesNotCallGraphExecutor() {
        RuntimeAgentWorkflowToolEntity tool = tool("wf-1", "query_team");
        stubWorkflow(tool);
        RuntimeAgentExecutionCancellation cancellation = new RuntimeAgentExecutionCancellation();
        cancellation.cancel();
        AgentScopeSupervisorRuntimeAdapter adapter = adapter(request -> {
            throw new AssertionError("cancelled run must not call model");
        });

        SupervisorRuntimeAdapter.SupervisorResult result = adapter.execute(
                new SupervisorRuntimeAdapter.SupervisorRequest(
                        agent(), config(false), List.of(tool),
                        Map.of("message", "查询", "sessionId", "s-pre", "projectCode", "qmssmp"),
                        null, SupervisorRuntimeAdapter.SupervisorEventSink.NOOP, cancellation));

        assertFalse(result.success());
        assertEquals("SUPERVISOR_CANCELLED", result.code());
        verify(graphExecutor, never()).execute(any(), any(), any(), any(), any());
    }

    @Test
    void cancelDuringWorkflowDoesNotMapToTimeoutAndDoesNotWriteSuccessMemory() throws Exception {
        RuntimeAgentWorkflowToolEntity tool = tool("wf-1", "query_team");
        stubWorkflow(tool);
        RuntimeAgentExecutionCancellation cancellation = new RuntimeAgentExecutionCancellation();
        CountDownLatch inWorkflow = new CountDownLatch(1);
        AtomicInteger graphCalls = new AtomicInteger();
        when(graphExecutor.execute(any(), any(), any(), any(), any())).thenAnswer(invocation -> {
            graphCalls.incrementAndGet();
            RuntimeGraphSpecExecutionCancellation workflowCancel = invocation.getArgument(3);
            inWorkflow.countDown();
            long deadline = System.currentTimeMillis() + 5_000;
            while (!workflowCancel.isCancelled() && System.currentTimeMillis() < deadline) {
                Thread.sleep(20);
            }
            return new RuntimeGraphSpecExecutionResult(false, "RUNTIME_GRAPH_CANCELLED",
                    "Workflow execution cancelled", null, null, List.of(), Map.of());
        });

        RuntimeChatMemoryStore memory = new RuntimeChatMemoryStore(20);
        AgentScopeSupervisorRuntimeAdapter adapter = adapterWithMemory(modelForPlanAndTool(), memory);
        java.util.concurrent.atomic.AtomicReference<SupervisorRuntimeAdapter.SupervisorResult> resultRef =
                new java.util.concurrent.atomic.AtomicReference<>();

        Thread runner = new Thread(() -> resultRef.set(adapter.execute(
                new SupervisorRuntimeAdapter.SupervisorRequest(
                        agent(), config(false), List.of(tool),
                        Map.of("message", "查询班组", "sessionId", "s-mid", "projectCode", "qmssmp"),
                        null, SupervisorRuntimeAdapter.SupervisorEventSink.NOOP, cancellation))));
        runner.start();
        assertTrue(inWorkflow.await(5, TimeUnit.SECONDS));
        cancellation.cancel();
        runner.join(10_000);

        assertEquals(1, graphCalls.get());
        assertTrue(memory.getHistory("s-mid").isEmpty(), "cancelled run must not write success chat memory");
        SupervisorRuntimeAdapter.SupervisorResult result = resultRef.get();
        assertFalse(result == null || result.success());
        assertEquals("SUPERVISOR_CANCELLED", result.code());
        assertFalse("SUPERVISOR_WORKFLOW_TIMEOUT".equals(result.code()));
    }

    @Test
    void parallelWorkflowsAreAllCancelledOnRequestCancel() throws Exception {
        RuntimeAgentWorkflowToolEntity teamTool = tool("wf-team", "query_team");
        RuntimeAgentWorkflowToolEntity ownerTool = tool("wf-owner", "query_owner");
        stubWorkflow(teamTool);
        stubWorkflow(ownerTool);
        RuntimeAgentExecutionCancellation cancellation = new RuntimeAgentExecutionCancellation();
        CountDownLatch bothStarted = new CountDownLatch(2);
        List<RuntimeGraphSpecExecutionCancellation> captured =
                Collections.synchronizedList(new ArrayList<>());
        when(graphExecutor.execute(any(), any(), any(), any(), any())).thenAnswer(invocation -> {
            RuntimeGraphSpecExecutionCancellation workflowCancel = invocation.getArgument(3);
            captured.add(workflowCancel);
            bothStarted.countDown();
            long deadline = System.currentTimeMillis() + 5_000;
            while (!workflowCancel.isCancelled() && System.currentTimeMillis() < deadline) {
                Thread.sleep(20);
            }
            return new RuntimeGraphSpecExecutionResult(false, "RUNTIME_GRAPH_CANCELLED",
                    "Workflow execution cancelled", null, null, List.of(), Map.of());
        });

        AgentScopeSupervisorRuntimeAdapter adapter = adapter(modelForParallelTools());
        Thread runner = new Thread(() -> adapter.execute(
                new SupervisorRuntimeAdapter.SupervisorRequest(
                        agent(), config(true), List.of(teamTool, ownerTool),
                        Map.of("message", "并行查询", "sessionId", "s-par", "projectCode", "qmssmp"),
                        null, SupervisorRuntimeAdapter.SupervisorEventSink.NOOP, cancellation)));
        runner.start();
        assertTrue(bothStarted.await(5, TimeUnit.SECONDS), "both parallel workflows should start");
        cancellation.cancel();
        runner.join(10_000);

        assertEquals(2, captured.size());
        assertTrue(captured.stream().allMatch(RuntimeGraphSpecExecutionCancellation::isCancelled));
    }

    @Test
    void cancelAfterSuccessfulWorkflowIsIdempotent() {
        RuntimeAgentWorkflowToolEntity tool = tool("wf-1", "query_team");
        stubWorkflow(tool);
        when(graphExecutor.execute(any(), any(), any(), any(), any())).thenReturn(
                new RuntimeGraphSpecExecutionResult(true, "OK", "done", null, null, List.of(), Map.of()));
        RuntimeAgentExecutionCancellation cancellation = new RuntimeAgentExecutionCancellation();
        AgentScopeSupervisorRuntimeAdapter adapter = adapter(modelForPlanAndToolThenAnswer());

        SupervisorRuntimeAdapter.SupervisorResult result = adapter.execute(
                new SupervisorRuntimeAdapter.SupervisorRequest(
                        agent(), config(false), List.of(tool),
                        Map.of("message", "查询", "sessionId", "s-done", "projectCode", "qmssmp"),
                        null, SupervisorRuntimeAdapter.SupervisorEventSink.NOOP, cancellation));

        assertTrue(result.success());
        cancellation.cancel();
        cancellation.cancel();
        assertTrue(result.success());
        assertEquals("SUPERVISOR_COMPLETED", result.code());
    }

    private RuntimeModelServiceClient modelForPlanAndTool() {
        AtomicInteger n = new AtomicInteger();
        return request -> {
            int call = n.incrementAndGet();
            if (call == 1) {
                return toolCalls(List.of(Map.of(
                        "id", "p1", "type", "function",
                        "function", Map.of(
                                "name", "record_supervisor_plan",
                                "arguments", "{\"summary\":\"查\",\"steps\":[\"查询班组\"],\"workflowToolNames\":[\"query_team\"]}"))));
            }
            if (call == 2) {
                return toolCalls(List.of(Map.of(
                        "id", "w1", "type", "function",
                        "function", Map.of("name", "query_team", "arguments", "{}"))));
            }
            return text("should-not-answer");
        };
    }

    private RuntimeModelServiceClient modelForPlanAndToolThenAnswer() {
        AtomicInteger n = new AtomicInteger();
        return request -> {
            int call = n.incrementAndGet();
            if (call == 1) {
                return toolCalls(List.of(Map.of(
                        "id", "p1", "type", "function",
                        "function", Map.of(
                                "name", "record_supervisor_plan",
                                "arguments", "{\"summary\":\"查\",\"steps\":[\"查询班组\"],\"workflowToolNames\":[\"query_team\"]}"))));
            }
            if (call == 2) {
                return toolCalls(List.of(Map.of(
                        "id", "w1", "type", "function",
                        "function", Map.of("name", "query_team", "arguments", "{}"))));
            }
            if (call == 3) {
                return toolCalls(List.of(Map.of(
                        "id", "f1", "type", "function",
                        "function", Map.of("name", "begin_final_answer", "arguments", "{}"))));
            }
            return text("完成");
        };
    }

    private RuntimeModelServiceClient modelForParallelTools() {
        AtomicInteger n = new AtomicInteger();
        return request -> {
            int call = n.incrementAndGet();
            if (call == 1) {
                return toolCalls(List.of(Map.of(
                        "id", "p1", "type", "function",
                        "function", Map.of(
                                "name", "record_supervisor_plan",
                                "arguments", "{\"summary\":\"并行\",\"steps\":[\"班组\",\"负责人\"],\"workflowToolNames\":[\"query_team\",\"query_owner\"]}"))));
            }
            if (call == 2) {
                return toolCalls(List.of(
                        Map.of("id", "t1", "type", "function",
                                "function", Map.of("name", "query_team", "arguments", "{}")),
                        Map.of("id", "o1", "type", "function",
                                "function", Map.of("name", "query_owner", "arguments", "{}"))));
            }
            return text("should-not");
        };
    }

    private ModelChatResult toolCalls(List<Map<String, Object>> calls) {
        try {
            return new ModelChatResult(200, "success",
                    new ModelChatData(null, "test", "test", new ModelUsage(1, 1, 2),
                            null, objectMapper.valueToTree(calls), "tool_calls"));
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private ModelChatResult text(String content) {
        return new ModelChatResult(200, "success",
                new ModelChatData(content, "test", "test", new ModelUsage(1, 1, 2),
                        null, null, "stop"));
    }

    private AgentScopeSupervisorRuntimeAdapter adapter(RuntimeModelServiceClient modelClient) {
        return adapterWithMemory(modelClient, new RuntimeChatMemoryStore(20));
    }

    private AgentScopeSupervisorRuntimeAdapter adapterWithMemory(RuntimeModelServiceClient modelClient,
                                                                 RuntimeChatMemoryStore memory) {
        SupervisorExecutionTraceService.TraceHandle handle =
                new SupervisorExecutionTraceService.TraceHandle("trace-1", "span-1", 1L, LocalDateTime.now());
        when(traceService.begin(any(), any(), any(), any())).thenReturn(handle);
        when(traceService.begin(any(), any(), any(), any(), any())).thenReturn(handle);
        when(traceService.beginOrResume(any(), any(), any(), any())).thenReturn(handle);
        when(traceService.beginOrResume(any(), any(), any(), any(), any())).thenReturn(handle);
        SupervisorToolPolicyService policy = new SupervisorToolPolicyService(
                traceService, mock(SupervisorApprovalInteractionService.class), objectMapper);
        return new AgentScopeSupervisorRuntimeAdapter(
                modelClient, null, null, workflowMapper, versionMapper, graphExecutor,
                mock(com.enterprise.ai.runtime.execution.RuntimeWorkflowInteractionSessionService.class),
                memory, policy, traceService, objectMapper);
    }

    private void stubWorkflow(RuntimeAgentWorkflowToolEntity tool) {
        RuntimeWorkflowDefinitionEntity workflow = new RuntimeWorkflowDefinitionEntity();
        workflow.setId(tool.getWorkflowId());
        workflow.setKeySlug(tool.getToolName());
        workflow.setName(tool.getToolName());
        workflow.setDescription(tool.getToolName());
        workflow.setInputSchemaJson("{\"type\":\"object\",\"additionalProperties\":true}");
        workflow.setStatus("ACTIVE");
        RuntimeWorkflowVersionEntity version = new RuntimeWorkflowVersionEntity();
        version.setId((long) Math.abs(tool.getWorkflowId().hashCode()));
        tool.setWorkflowVersionId(version.getId());
        version.setWorkflowId(tool.getWorkflowId());
        version.setVersion("1.0.0");
        version.setStatus("ACTIVE");
        version.setGraphSpecSnapshotJson("{\"entryNodeId\":\"a\",\"nodes\":[{\"id\":\"a\",\"type\":\"ANSWER\"}]}");
        when(workflowMapper.selectById(tool.getWorkflowId())).thenReturn(workflow);
        when(versionMapper.selectById(version.getId())).thenReturn(version);
        workflowTargets.put(workflow.getId(), workflow);
        workflowVersions.put(version.getId(), version);
        when(workflowMapper.selectBatchIds(any())).thenAnswer(invocation -> {
            List<?> ids = invocation.getArgument(0);
            if (ids == null) return List.copyOf(workflowTargets.values());
            return ids.stream().map(String::valueOf).map(workflowTargets::get)
                    .filter(java.util.Objects::nonNull).toList();
        });
        when(versionMapper.selectBatchIds(any())).thenAnswer(invocation -> {
            List<?> ids = invocation.getArgument(0);
            if (ids == null) return List.copyOf(workflowVersions.values());
            return ids.stream().filter(Number.class::isInstance).map(Number.class::cast)
                    .map(Number::longValue).map(workflowVersions::get)
                    .filter(java.util.Objects::nonNull).toList();
        });
    }

    private RuntimeAgentWorkflowToolEntity tool(String workflowId, String toolName) {
        RuntimeAgentWorkflowToolEntity tool = new RuntimeAgentWorkflowToolEntity();
        tool.setAgentId("agent-1");
        tool.setAgentConfigVersionId(7L);
        tool.setWorkflowId(workflowId);
        tool.setToolName(toolName);
        tool.setRiskLevel("READ");
        tool.setPermissionKey(toolName + ":read");
        tool.setEnabled(true);
        tool.setReadOnly(true);
        return tool;
    }

    private RuntimeAgentView agent() {
        return new RuntimeAgentView(
                "agent-1", 7L, "qmssmp", "team-assistant", "班组助手", null,
                "PROJECT", null, true,
                7L, 7L, 1, "ACTIVE", "AGENTSCOPE", 2, null, null);
    }

    private RuntimeAgentConfigVersionEntity config(boolean parallelReadOnly) {
        RuntimeAgentConfigVersionEntity config = new RuntimeAgentConfigVersionEntity();
        config.setId(7L);
        config.setAgentId("agent-1");
        config.setVersionNo(1);
        config.setRuntimeType("AGENTSCOPE");
        config.setSystemPrompt("你是班组助手");
        config.setModelInstanceId("model-1");
        config.setMaxPlanSteps(6);
        config.setMaxWorkflowCalls(4);
        config.setMaxReplans(1);
        config.setTotalTimeoutMs(15_000);
        config.setWorkflowTimeoutMs(10_000);
        config.setPageBridgeTimeoutMs(2_000);
        config.setParallelReadOnly(parallelReadOnly);
        config.setPolicyProfile("DEV_ALLOW_ALL");
        config.setToolCatalogMode("ALLOW_LIST");
        config.setConfigJson("{}");
        return config;
    }
}
