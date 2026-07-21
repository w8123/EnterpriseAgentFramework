package com.enterprise.ai.runtime.supervisor;

import com.enterprise.ai.runtime.agent.RuntimeAgentConfigVersionEntity;
import com.enterprise.ai.runtime.agent.RuntimeAgentView;
import com.enterprise.ai.runtime.agent.RuntimeAgentWorkflowToolEntity;
import com.enterprise.ai.runtime.chat.RuntimeChatMemoryStore;
import com.enterprise.ai.runtime.client.model.RuntimeModelServiceClient;
import com.enterprise.ai.runtime.client.model.RuntimeModelServiceClient.ModelChatData;
import com.enterprise.ai.runtime.client.model.RuntimeModelServiceClient.ModelChatResult;
import com.enterprise.ai.runtime.client.model.RuntimeModelServiceClient.ModelUsage;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AgentScopeSupervisorRuntimeAdapterTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final RuntimeWorkflowDefinitionMapper workflowMapper = mock(RuntimeWorkflowDefinitionMapper.class);
    private final RuntimeWorkflowVersionMapper versionMapper = mock(RuntimeWorkflowVersionMapper.class);
    private final RuntimeGraphSpecExecutor graphExecutor = mock(RuntimeGraphSpecExecutor.class);
    private final SupervisorExecutionTraceService traceService = mock(SupervisorExecutionTraceService.class);
    private final Map<String, RuntimeWorkflowDefinitionEntity> workflowTargets = new LinkedHashMap<>();
    private final Map<Long, RuntimeWorkflowVersionEntity> workflowVersions = new LinkedHashMap<>();

    @Test
    void answersDirectlyWithAnImplicitZeroToolPlan() {
        AgentScopeSupervisorRuntimeAdapter adapter = adapter(model(List.of(text("你好，我是班组助手"))));
        List<String> events = new ArrayList<>();

        SupervisorRuntimeAdapter.SupervisorResult result = adapter.execute(new SupervisorRuntimeAdapter.SupervisorRequest(
                agent(), config(false), List.of(), Map.of(
                        "message", "你好", "sessionId", "s-zero", "projectCode", "qmssmp"),
                null, (event, data) -> {
                    events.add(event);
                }));

        assertTrue(result.success(), String.valueOf(result.metadata()));
        assertEquals("你好，我是班组助手", result.answer());
        assertTrue(events.contains("message.delta"));
        assertEquals(0, result.metadata().get("planCount"),
                "implicit direct decision must not inflate AgentScope planCount");
        assertEquals("DIRECT", result.metadata().get("decisionMode"));
        assertEquals(1, result.metadata().get("modelRoundCount"));
        assertEquals("buffered_direct", result.metadata().get("streamMode"));
        assertEquals(false, result.metadata().get("workflowSelected"));
        assertEquals(0, result.metadata().get("workflowCallCount"));
        assertEquals("s-zero", result.metadata().get("sessionId"));
        assertTrue(events.stream().noneMatch("message.delta"::equals)
                        || "buffered_direct".equals(result.metadata().get("streamMode")),
                "DIRECT response must not depend on a forced PUBLIC_FINAL stream");
    }

    @Test
    void executesMultipleReadOnlyWorkflowCallsInParallelAndAggregatesAnswer() throws Exception {
        RuntimeAgentWorkflowToolEntity teamTool = tool("wf-team", "query_team");
        RuntimeAgentWorkflowToolEntity ownerTool = tool("wf-owner", "query_owner");
        stubWorkflow(teamTool);
        stubWorkflow(ownerTool);
        AtomicInteger active = new AtomicInteger();
        AtomicInteger maxActive = new AtomicInteger();
        when(graphExecutor.execute(any(), any(), any(), any(), any())).thenAnswer(invocation -> {
            int current = active.incrementAndGet();
            maxActive.accumulateAndGet(current, Math::max);
            Thread.sleep(150);
            active.decrementAndGet();
            Map<String, Object> input = invocation.getArgument(1);
            return success(String.valueOf(input.get("kind")));
        });
        AgentScopeSupervisorRuntimeAdapter adapter = adapter(model(List.of(
                calls(call("plan-1", "record_supervisor_plan", Map.of(
                        "summary", "查询班组与负责人", "steps", List.of("查询班组", "查询负责人"),
                        "workflowToolNames", List.of("query_team", "query_owner")))),
                calls(
                        call("team-1", "query_team", Map.of("kind", "team")),
                        call("owner-1", "query_owner", Map.of("kind", "owner"))),
                text("班组与负责人信息已汇总"),
                text("班组与负责人信息已汇总"))));

        SupervisorRuntimeAdapter.SupervisorResult result = adapter.execute(new SupervisorRuntimeAdapter.SupervisorRequest(
                agent(), config(true), List.of(teamTool, ownerTool), Map.of(
                        "message", "查询班组及负责人", "sessionId", "s-multi", "projectCode", "qmssmp")));

        assertTrue(result.success());
        assertEquals(2, result.metadata().get("workflowCallCount"));
        assertEquals(2, maxActive.get());
        assertEquals("班组与负责人信息已汇总", result.answer());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> steps = (List<Map<String, Object>>) result.metadata().get("steps");
        assertTrue(steps.stream().anyMatch(s ->
                "workflow".equals(s.get("name")) && "completed".equals(s.get("state"))));
    }

    @Test
    void surfacesNonBlockingWorkflowPresentationWithFinalAgentAnswer() throws Exception {
        RuntimeAgentWorkflowToolEntity tool = tool("wf-team", "query_team");
        stubWorkflow(tool);
        Map<String, Object> uiRequest = Map.of(
                "schemaVersion", "1.0",
                "type", "PRESENT_OUTPUT",
                "component", "list_card",
                "data", Map.of("records", List.of(Map.of("name", "一班"))));
        when(graphExecutor.execute(any(), any(), any(), any(), any())).thenReturn(
                new RuntimeGraphSpecExecutionResult(
                        true,
                        "RUNTIME_GRAPH_EXECUTED",
                        "已展示查询结果",
                        "answer",
                        "ANSWER",
                        List.of(),
                        Map.of("uiRequest", uiRequest, "displayOnly", true)));
        AgentScopeSupervisorRuntimeAdapter adapter = adapter(model(List.of(
                calls(call("plan-list", "record_supervisor_plan", Map.of(
                        "summary", "查询并展示班组", "steps", List.of("查询班组"),
                        "workflowToolNames", List.of("query_team")))),
                calls(call("workflow-list", "query_team", Map.of("teamName", "一班"))),
                text("已展示班组查询结果"),
                text("已展示班组查询结果"))));

        SupervisorRuntimeAdapter.SupervisorResult result = adapter.execute(
                new SupervisorRuntimeAdapter.SupervisorRequest(
                        agent(), config(false), List.of(tool), Map.of(
                        "message", "查询一班", "sessionId", "s-list-card", "projectCode", "qmssmp")));

        assertTrue(result.success(), String.valueOf(result.metadata()));
        assertEquals(uiRequest, result.uiRequest());
        assertEquals("已展示班组查询结果", result.answer());
    }

    @Test
    void serializesPageActionsEvenWhenParallelToolExecutionIsEnabled() throws Exception {
        RuntimeAgentWorkflowToolEntity firstPageAction = pageActionTool("wf-page-team", "open_team_page");
        RuntimeAgentWorkflowToolEntity secondPageAction = pageActionTool("wf-page-owner", "query_owner_on_page");
        stubWorkflow(firstPageAction);
        stubWorkflow(secondPageAction);
        AtomicInteger active = new AtomicInteger();
        AtomicInteger maxActive = new AtomicInteger();
        when(graphExecutor.execute(any(), any(), any(), any(), any())).thenAnswer(invocation -> {
            int current = active.incrementAndGet();
            maxActive.accumulateAndGet(current, Math::max);
            Thread.sleep(120);
            active.decrementAndGet();
            return success("page action completed");
        });
        AgentScopeSupervisorRuntimeAdapter adapter = adapter(model(List.of(
                calls(call("plan-page", "record_supervisor_plan", Map.of(
                        "summary", "打开班组页面并在页面查询负责人",
                        "steps", List.of("打开班组页面", "在页面查询负责人"),
                        "workflowToolNames", List.of("open_team_page", "query_owner_on_page")))),
                calls(
                        call("page-1", "open_team_page", Map.of()),
                        call("page-2", "query_owner_on_page", Map.of())),
                text("页面操作已完成"),
                text("页面操作已完成"))));

        SupervisorRuntimeAdapter.SupervisorResult result = adapter.execute(new SupervisorRuntimeAdapter.SupervisorRequest(
                agent(), config(true), List.of(firstPageAction, secondPageAction), Map.of(
                        "message", "请打开班组页面，并在页面上查询负责人",
                        "sessionId", "s-page", "projectCode", "qmssmp")));

        assertTrue(result.success());
        assertEquals(2, result.metadata().get("workflowCallCount"));
        assertEquals(1, maxActive.get());
    }

    @Test
    void requiresBoundedReplanAfterFailureBeforeRetryingWorkflow() throws Exception {
        RuntimeAgentWorkflowToolEntity tool = tool("wf-team", "query_team");
        stubWorkflow(tool);
        AtomicInteger execution = new AtomicInteger();
        when(graphExecutor.execute(any(), any(), any(), any(), any())).thenAnswer(invocation -> execution.incrementAndGet() == 1
                ? new RuntimeGraphSpecExecutionResult(false, "UPSTREAM_FAILED", "第一次失败",
                null, null, List.of(), Map.of())
                : success("第二次成功"));
        AgentScopeSupervisorRuntimeAdapter adapter = adapter(model(List.of(
                calls(call("plan-1", "record_supervisor_plan", Map.of(
                        "summary", "先查询", "steps", List.of("查询班组"),
                        "workflowToolNames", List.of("query_team")))),
                calls(call("team-1", "query_team", Map.of())),
                calls(call("replan-1", "record_supervisor_plan", Map.of(
                        "summary", "失败后重试", "steps", List.of("调整参数后重试"),
                        "workflowToolNames", List.of("query_team"), "reason", "上游暂时失败"))),
                calls(call("team-2", "query_team", Map.of())),
                text("重规划后查询成功"),
                text("重规划后查询成功"))));

        SupervisorRuntimeAdapter.SupervisorResult result = adapter.execute(new SupervisorRuntimeAdapter.SupervisorRequest(
                agent(), config(false), List.of(tool), Map.of(
                        "message", "查询班组", "sessionId", "s-replan", "projectCode", "qmssmp")));

        assertTrue(result.success(), String.valueOf(result.metadata()));
        assertEquals(2, result.metadata().get("planCount"));
        assertEquals(1, result.metadata().get("replanCount"));
        assertEquals(2, result.metadata().get("workflowCallCount"));
        assertEquals(2, execution.get());
    }

    @Test
    void executesPublishedWorkflowWithModelDefaultFromPinnedVersionSnapshot() throws Exception {
        RuntimeAgentWorkflowToolEntity tool = tool("wf-model", "classify_intent");
        stubWorkflow(tool, "published-model", "newer-draft-model");
        List<Map<String, Object>> workflowInputs = new ArrayList<>();
        when(graphExecutor.execute(any(), any(), any(), any(), any())).thenAnswer(invocation -> {
            workflowInputs.add(invocation.getArgument(1));
            return success("classified");
        });
        AgentScopeSupervisorRuntimeAdapter adapter = adapter(model(List.of(
                calls(call("plan-model", "record_supervisor_plan", Map.of(
                        "summary", "识别意图", "steps", List.of("执行分类 Workflow"),
                        "workflowToolNames", List.of("classify_intent")))),
                calls(call("workflow-model", "classify_intent", Map.of())),
                text("分类完成"),
                text("分类完成"))));

        SupervisorRuntimeAdapter.SupervisorResult result = adapter.execute(new SupervisorRuntimeAdapter.SupervisorRequest(
                agent(), config(false), List.of(tool), Map.of(
                        "message", "我要重置条件", "sessionId", "s-model", "projectCode", "qmssmp",
                        "modelInstanceId", "untrusted-input-model")));

        assertTrue(result.success());
        assertEquals(1, workflowInputs.size());
        assertEquals("published-model", workflowInputs.get(0).get("workflowDefaultModelInstanceId"));
    }

    @Test
    void resumesTheExactNextStructuredWorkflowAfterInteraction() throws Exception {
        RuntimeAgentWorkflowToolEntity queryTool = tool("wf-query", "query_team");
        RuntimeAgentWorkflowToolEntity disableTool = tool("wf-disable", "disable_team");
        stubWorkflow(queryTool);
        stubWorkflow(disableTool);
        when(graphExecutor.execute(any(), any(), any(), any(), any())).thenReturn(success("已停用"));
        AgentScopeSupervisorRuntimeAdapter adapter = adapter(model(List.of(
                calls(call("disable-1", "disable_team", Map.of("teamId", "team-1"))),
                calls(call("final-1", "begin_final_answer", Map.of())),
                text("班组已停用"),
                text("班组已停用"))));
        Map<String, Object> recordedPlan = Map.of(
                "summary", "查询并停用班组",
                "steps", List.of("查询班组", "停用班组"),
                "workflowToolNames", List.of("query_team", "disable_team"),
                "planNo", 1);
        Map<String, Object> continuation = new LinkedHashMap<>();
        continuation.put("continuationSchemaVersion", 2);
        continuation.put("agentId", "agent-1");
        continuation.put("agentConfigVersionId", 7L);
        continuation.put("traceId", "trace-1");
        continuation.put("planNo", 1);
        continuation.put("waitingToolName", "query_team");
        continuation.put("plannedWorkflowToolNames", List.of("query_team", "disable_team"));
        continuation.put("plannedWorkflowCursor", 0);
        continuation.put("completedWorkflowToolNames", List.of());
        continuation.put("recordedPlan", recordedPlan);
        continuation.put("originalInput", Map.of(
                "message", "停用一班", "sessionId", "s-resume", "userId", "u-1",
                "appId", "qmssmp", "projectCode", "qmssmp"));

        SupervisorRuntimeAdapter.SupervisorResult result = adapter.continueAfterWorkflowInteraction(
                continuation,
                Map.of("success", true, "status", "COMPLETED", "code", "OK", "answer", "找到一班"),
                new SupervisorRuntimeAdapter.SupervisorRequest(
                        agent(), config(false), List.of(queryTool, disableTool), Map.of(
                        "message", "确认", "sessionId", "s-resume", "userId", "u-1",
                        "appId", "qmssmp", "projectCode", "qmssmp", "traceId", "trace-1")));

        assertTrue(result.success(), String.valueOf(result.metadata()));
        assertEquals("班组已停用", result.answer());
        assertEquals(1, result.metadata().get("workflowCallCount"));
        assertEquals(2, result.metadata().get("plannedWorkflowCursor"));
    }

    @Test
    void rejectsLegacyOrUnstructuredInteractionContinuation() {
        RuntimeAgentWorkflowToolEntity queryTool = tool("wf-query", "query_team");
        stubWorkflow(queryTool);
        AgentScopeSupervisorRuntimeAdapter adapter = adapter(model(List.of()));

        SupervisorRuntimeAdapter.SupervisorResult result = adapter.continueAfterWorkflowInteraction(
                Map.of(
                        "traceId", "trace-1",
                        "waitingToolName", "query_team",
                        "recordedPlan", Map.of("steps", List.of("query_team"))),
                Map.of("success", true, "status", "COMPLETED", "answer", "ok"),
                new SupervisorRuntimeAdapter.SupervisorRequest(
                        agent(), config(false), List.of(queryTool), Map.of("traceId", "trace-1")));

        assertFalse(result.success());
        assertEquals("RUNTIME_INTERACTION_CONTINUATION_INVALID", result.code());
    }

    private AgentScopeSupervisorRuntimeAdapter adapter(RuntimeModelServiceClient modelClient) {
        SupervisorExecutionTraceService.TraceHandle handle =
                new SupervisorExecutionTraceService.TraceHandle("trace-1", "span-1", 1L, LocalDateTime.now());
        when(traceService.begin(any(), any(), any(), any())).thenReturn(handle);
        when(traceService.begin(any(), any(), any(), any(), any())).thenReturn(handle);
        when(traceService.beginOrResume(any(), any(), any(), any())).thenReturn(handle);
        when(traceService.beginOrResume(any(), any(), any(), any(), any())).thenReturn(handle);
        when(traceService.resume(any())).thenReturn(handle);
        SupervisorToolPolicyService policy = new SupervisorToolPolicyService(
                traceService, mock(SupervisorApprovalInteractionService.class), objectMapper);
        return new AgentScopeSupervisorRuntimeAdapter(
                modelClient,
                null,
                workflowMapper,
                versionMapper,
                graphExecutor,
                mock(com.enterprise.ai.runtime.execution.RuntimeWorkflowInteractionSessionService.class),
                new RuntimeChatMemoryStore(20),
                policy,
                traceService,
                objectMapper);
    }

    private RuntimeModelServiceClient model(List<ModelChatData> responses) {
        AtomicInteger index = new AtomicInteger();
        return request -> {
            int responseIndex = index.getAndIncrement();
            if (responseIndex >= responses.size()) throw new IllegalStateException("Unexpected model call");
            return new ModelChatResult(200, "success", responses.get(responseIndex));
        };
    }

    private ModelChatData text(String content) {
        return new ModelChatData(content, "test", "test", new ModelUsage(1, 1, 2),
                null, null, "stop");
    }

    @SafeVarargs
    private final ModelChatData calls(Map<String, Object>... calls) {
        return new ModelChatData(null, "test", "test", new ModelUsage(1, 1, 2),
                null, objectMapper.valueToTree(List.of(calls)), "tool_calls");
    }

    private Map<String, Object> call(String id, String name, Map<String, Object> args) throws Exception {
        return Map.of(
                "id", id,
                "type", "function",
                "function", Map.of("name", name, "arguments", objectMapper.writeValueAsString(args)));
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
        config.setTotalTimeoutMs(10_000);
        config.setWorkflowTimeoutMs(5_000);
        config.setPageBridgeTimeoutMs(2_000);
        config.setParallelReadOnly(parallelReadOnly);
        config.setPolicyProfile("DEV_ALLOW_ALL");
        config.setToolCatalogMode("ALLOW_LIST");
        config.setConfigJson("{}");
        return config;
    }

    private RuntimeAgentView agent() {
        return new RuntimeAgentView(
                "agent-1", 7L, "qmssmp", "team-assistant", "班组助手", null,
                "PROJECT", null, true,
                7L, 7L, 1, "ACTIVE", "AGENTSCOPE", 2, null, null);
    }

    private RuntimeAgentWorkflowToolEntity tool(String workflowId, String toolName) {
        RuntimeAgentWorkflowToolEntity tool = new RuntimeAgentWorkflowToolEntity();
        tool.setAgentId("agent-1");
        tool.setAgentConfigVersionId(7L);
        tool.setWorkflowId(workflowId);
        tool.setToolName(toolName);
        tool.setRiskLevel("READ");
        tool.setPermissionKey(toolName + ":read");
        tool.setReadOnly(true);
        tool.setEnabled(true);
        return tool;
    }

    private RuntimeAgentWorkflowToolEntity pageActionTool(String workflowId, String toolName) {
        RuntimeAgentWorkflowToolEntity tool = tool(workflowId, toolName);
        tool.setRiskLevel("PAGE_ACTION");
        tool.setPermissionKey(toolName + ":page");
        tool.setReadOnly(false);
        return tool;
    }

    private void stubWorkflow(RuntimeAgentWorkflowToolEntity tool) {
        stubWorkflow(tool, null, null);
    }

    private void stubWorkflow(RuntimeAgentWorkflowToolEntity tool,
                              String publishedModelInstanceId,
                              String currentDraftModelInstanceId) {
        RuntimeWorkflowDefinitionEntity workflow = new RuntimeWorkflowDefinitionEntity();
        workflow.setId(tool.getWorkflowId());
        workflow.setKeySlug(tool.getToolName());
        workflow.setName(tool.getToolName());
        workflow.setDescription(tool.getToolName());
        workflow.setInputSchemaJson("{\"type\":\"object\",\"additionalProperties\":true}");
        workflow.setStatus("ACTIVE");
        workflow.setDefaultModelInstanceId(currentDraftModelInstanceId);
        RuntimeWorkflowVersionEntity version = new RuntimeWorkflowVersionEntity();
        version.setId((long) Math.abs(tool.getWorkflowId().hashCode()));
        tool.setWorkflowVersionId(version.getId());
        version.setWorkflowId(tool.getWorkflowId());
        version.setVersion("1.0.0");
        version.setStatus("ACTIVE");
        version.setGraphSpecSnapshotJson("{\"nodes\":[],\"edges\":[]}");
        if (publishedModelInstanceId != null) {
            version.setSnapshotJson("{\"defaultModelInstanceId\":\"" + publishedModelInstanceId + "\"}");
        }
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

    private RuntimeGraphSpecExecutionResult success(String answer) {
        return new RuntimeGraphSpecExecutionResult(true, "OK", answer,
                null, null, new ArrayList<>(), Map.of());
    }
}
