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
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

    @Test
    void answersDirectlyWithAnImplicitZeroToolPlan() {
        AgentScopeSupervisorRuntimeAdapter adapter = adapter(model(List.of(text("你好，我是班组助手"))));
        List<String> events = new ArrayList<>();

        SupervisorRuntimeAdapter.SupervisorResult result = adapter.execute(new SupervisorRuntimeAdapter.SupervisorRequest(
                agent(), config(false), List.of(), Map.of(
                        "message", "你好", "sessionId", "s-zero", "projectCode", "qmssmp"),
                null, (event, data) -> events.add(event)));

        assertTrue(result.success());
        assertEquals("你好，我是班组助手", result.answer());
        assertEquals(1, result.metadata().get("planCount"));
        assertEquals(0, result.metadata().get("workflowCallCount"));
        assertEquals("s-zero", result.metadata().get("sessionId"));
        assertTrue(events.contains("supervisor.step"));
    }

    @Test
    void executesMultipleReadOnlyWorkflowCallsInParallelAndAggregatesAnswer() throws Exception {
        RuntimeAgentWorkflowToolEntity teamTool = tool("wf-team", "query_team");
        RuntimeAgentWorkflowToolEntity ownerTool = tool("wf-owner", "query_owner");
        stubWorkflow(teamTool);
        stubWorkflow(ownerTool);
        AtomicInteger active = new AtomicInteger();
        AtomicInteger maxActive = new AtomicInteger();
        when(graphExecutor.execute(any(), any())).thenAnswer(invocation -> {
            int current = active.incrementAndGet();
            maxActive.accumulateAndGet(current, Math::max);
            Thread.sleep(150);
            active.decrementAndGet();
            Map<String, Object> input = invocation.getArgument(1);
            return success(String.valueOf(input.get("kind")));
        });
        AgentScopeSupervisorRuntimeAdapter adapter = adapter(model(List.of(
                calls(call("plan-1", "record_supervisor_plan", Map.of(
                        "summary", "查询班组与负责人", "steps", List.of("查询班组", "查询负责人")))),
                calls(
                        call("team-1", "query_team", Map.of("kind", "team")),
                        call("owner-1", "query_owner", Map.of("kind", "owner"))),
                text("班组与负责人信息已汇总"))));

        SupervisorRuntimeAdapter.SupervisorResult result = adapter.execute(new SupervisorRuntimeAdapter.SupervisorRequest(
                agent(), config(true), List.of(teamTool, ownerTool), Map.of(
                        "message", "查询班组及负责人", "sessionId", "s-multi", "projectCode", "qmssmp")));

        assertTrue(result.success());
        assertEquals(2, result.metadata().get("workflowCallCount"));
        assertEquals(2, maxActive.get());
        assertEquals("班组与负责人信息已汇总", result.answer());
    }

    @Test
    void serializesPageActionsEvenWhenParallelToolExecutionIsEnabled() throws Exception {
        RuntimeAgentWorkflowToolEntity firstPageAction = pageActionTool("wf-page-team", "open_team_page");
        RuntimeAgentWorkflowToolEntity secondPageAction = pageActionTool("wf-page-owner", "query_owner_on_page");
        stubWorkflow(firstPageAction);
        stubWorkflow(secondPageAction);
        AtomicInteger active = new AtomicInteger();
        AtomicInteger maxActive = new AtomicInteger();
        when(graphExecutor.execute(any(), any())).thenAnswer(invocation -> {
            int current = active.incrementAndGet();
            maxActive.accumulateAndGet(current, Math::max);
            Thread.sleep(120);
            active.decrementAndGet();
            return success("page action completed");
        });
        AgentScopeSupervisorRuntimeAdapter adapter = adapter(model(List.of(
                calls(call("plan-page", "record_supervisor_plan", Map.of(
                        "summary", "打开班组页面并在页面查询负责人",
                        "steps", List.of("打开班组页面", "在页面查询负责人")))),
                calls(
                        call("page-1", "open_team_page", Map.of()),
                        call("page-2", "query_owner_on_page", Map.of())),
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
        when(graphExecutor.execute(any(), any())).thenAnswer(invocation -> execution.incrementAndGet() == 1
                ? new RuntimeGraphSpecExecutionResult(false, "UPSTREAM_FAILED", "第一次失败",
                null, null, List.of(), Map.of())
                : success("第二次成功"));
        AgentScopeSupervisorRuntimeAdapter adapter = adapter(model(List.of(
                calls(call("plan-1", "record_supervisor_plan", Map.of(
                        "summary", "先查询", "steps", List.of("查询班组")))),
                calls(call("team-1", "query_team", Map.of())),
                calls(call("replan-1", "record_supervisor_plan", Map.of(
                        "summary", "失败后重试", "steps", List.of("调整参数后重试"), "reason", "上游暂时失败"))),
                calls(call("team-2", "query_team", Map.of())),
                text("重规划后查询成功"))));

        SupervisorRuntimeAdapter.SupervisorResult result = adapter.execute(new SupervisorRuntimeAdapter.SupervisorRequest(
                agent(), config(false), List.of(tool), Map.of(
                        "message", "查询班组", "sessionId", "s-replan", "projectCode", "qmssmp")));

        assertTrue(result.success());
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
        when(graphExecutor.execute(any(), any())).thenAnswer(invocation -> {
            workflowInputs.add(invocation.getArgument(1));
            return success("classified");
        });
        AgentScopeSupervisorRuntimeAdapter adapter = adapter(model(List.of(
                calls(call("plan-model", "record_supervisor_plan", Map.of(
                        "summary", "识别意图", "steps", List.of("执行分类 Workflow")))),
                calls(call("workflow-model", "classify_intent", Map.of())),
                text("分类完成"))));

        SupervisorRuntimeAdapter.SupervisorResult result = adapter.execute(new SupervisorRuntimeAdapter.SupervisorRequest(
                agent(), config(false), List.of(tool), Map.of(
                        "message", "我要重置条件", "sessionId", "s-model", "projectCode", "qmssmp",
                        "modelInstanceId", "untrusted-input-model")));

        assertTrue(result.success());
        assertEquals(1, workflowInputs.size());
        assertEquals("published-model", workflowInputs.get(0).get("workflowDefaultModelInstanceId"));
    }

    private AgentScopeSupervisorRuntimeAdapter adapter(RuntimeModelServiceClient modelClient) {
        when(traceService.begin(any(), any(), any(), any())).thenReturn(
                new SupervisorExecutionTraceService.TraceHandle("trace-1", "span-1", 1L, LocalDateTime.now()));
        SupervisorToolPolicyService policy = new SupervisorToolPolicyService(
                traceService, mock(SupervisorApprovalInteractionService.class), objectMapper);
        return new AgentScopeSupervisorRuntimeAdapter(
                modelClient,
                workflowMapper,
                versionMapper,
                graphExecutor,
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
    }

    private RuntimeGraphSpecExecutionResult success(String answer) {
        return new RuntimeGraphSpecExecutionResult(true, "OK", answer,
                null, null, new ArrayList<>(), Map.of());
    }
}
