package com.enterprise.ai.runtime.supervisor;

import com.enterprise.ai.runtime.agent.RuntimeAgentConfigVersionEntity;
import com.enterprise.ai.runtime.agent.RuntimeAgentView;
import com.enterprise.ai.runtime.agent.RuntimeAgentWorkflowToolEntity;
import com.enterprise.ai.runtime.chat.RuntimeChatMemoryStore;
import com.enterprise.ai.runtime.client.control.RuntimeControlCatalogClient;
import com.enterprise.ai.runtime.client.model.RuntimeModelServiceClient;
import com.enterprise.ai.runtime.client.model.RuntimeModelServiceClient.ModelChatData;
import com.enterprise.ai.runtime.client.model.RuntimeModelServiceClient.ModelChatResult;
import com.enterprise.ai.runtime.client.model.RuntimeModelServiceClient.ModelUsage;
import com.enterprise.ai.runtime.execution.RuntimeGraphSpecExecutionResult;
import com.enterprise.ai.runtime.execution.RuntimeGraphSpecExecutor;
import com.enterprise.ai.runtime.memory.RuntimeSessionMemoryService;
import com.enterprise.ai.runtime.memory.RuntimeToolResultArtifactService;
import com.enterprise.ai.runtime.workflow.RuntimeWorkflowDefinitionEntity;
import com.enterprise.ai.runtime.workflow.RuntimeWorkflowDefinitionMapper;
import com.enterprise.ai.runtime.workflow.RuntimeWorkflowVersionEntity;
import com.enterprise.ai.runtime.workflow.RuntimeWorkflowVersionMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
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
    void forcedPublicFinalPassRecoversOnceFromContextOverflowWithoutRepeatingWorkflow()
            throws Exception {
        RuntimeAgentWorkflowToolEntity workflowTool = tool("wf-query", "query_team");
        stubWorkflow(workflowTool);
        when(graphExecutor.execute(any(), any(), any(), any(), any()))
                .thenReturn(success("workflow completed once"));
        AtomicInteger toolEnabledCalls = new AtomicInteger();
        AtomicInteger noToolCalls = new AtomicInteger();
        AtomicInteger compactionCalls = new AtomicInteger();
        ModelChatData planResponse = calls(call(
                "plan-1",
                "record_supervisor_plan",
                Map.of(
                        "summary", "query the team",
                        "steps", List.of("run query_team"),
                        "workflowToolNames", List.of("query_team"))));
        ModelChatData workflowResponse = calls(call(
                "workflow-1", "query_team", Map.of()));
        RuntimeModelServiceClient modelClient = request -> {
            if (request.getTools() != null) {
                return switch (toolEnabledCalls.incrementAndGet()) {
                    case 1 -> new ModelChatResult(200, "success", planResponse);
                    case 2 -> new ModelChatResult(200, "success", workflowResponse);
                    case 3 -> new ModelChatResult(200, "success", text("internal answer draft"));
                    default -> throw new IllegalStateException("Unexpected tool-enabled model call");
                };
            }
            return switch (noToolCalls.incrementAndGet()) {
                case 1 -> new ModelChatResult(
                        400,
                        "context_length_exceeded: maximum context length reached",
                        null);
                case 2 -> {
                    compactionCalls.incrementAndGet();
                    yield new ModelChatResult(200, "success", text(
                            "INTENT: answer the query\n"
                                    + "VERIFIED STATE: workflow returned successfully\n"
                                    + "COMPLETED ACTIONS: query_team completed once\n"
                                    + "PENDING: provide the final answer"));
                }
                case 3 -> new ModelChatResult(200, "success", text("final answer after compaction"));
                default -> throw new IllegalStateException("Unexpected no-tool model call");
            };
        };
        RuntimeSessionMemoryService memoryService = RuntimeSessionMemoryService.transientOnly();
        RuntimeContextEngineeringProperties properties = new RuntimeContextEngineeringProperties(
                true,
                false,
                true,
                24,
                24_000,
                10,
                2,
                60_000,
                false,
                80_000,
                2_000,
                16_777_216,
                12_000,
                24,
                200,
                60_000,
                "test-key",
                "");
        @SuppressWarnings("unchecked")
        ObjectProvider<RuntimeToolResultArtifactService> artifactProvider = mock(ObjectProvider.class);
        when(artifactProvider.getIfAvailable()).thenReturn(null);
        RuntimeContextEngineeringService contextEngineeringService =
                new RuntimeContextEngineeringService(
                        properties,
                        modelClient,
                        null,
                        objectMapper,
                        memoryService,
                        artifactProvider);
        AgentScopeSupervisorRuntimeAdapter adapter = adapterWithContextEngineering(
                modelClient, memoryService, contextEngineeringService);

        SupervisorRuntimeAdapter.SupervisorResult result = adapter.execute(
                new SupervisorRuntimeAdapter.SupervisorRequest(
                        agent(),
                        config(false),
                        List.of(workflowTool),
                        Map.of("message", "query the team", "sessionId", "s-final-overflow")));

        assertTrue(result.success(), result.code() + " " + result.answer() + " " + result.metadata());
        assertEquals("final answer after compaction", result.answer());
        assertEquals(3, toolEnabledCalls.get());
        assertEquals(3, noToolCalls.get());
        assertEquals(1, compactionCalls.get());
        verify(graphExecutor).execute(any(), any(), any(), any(), any());
        @SuppressWarnings("unchecked")
        Map<String, Object> modelMetadata =
                (Map<String, Object>) result.metadata().get("model");
        assertEquals(1, modelMetadata.get("contextOverflowRecoveryCount"));
    }

    @Test
    void ruleFirstPageQueryUsesNaturalLanguageAndPublishedPageSchema() throws Exception {
        RuntimeAgentWorkflowToolEntity pageQuery = pageActionTool("wf-page-orders", "mall_order_page_query");
        pageQuery.setInputSchemaOverrideJson("""
                {
                  "type":"object",
                  "properties":{
                    "status":{"type":"integer","description":"订单状态：0待付款，1待发货，4已关闭"},
                    "pageNum":{"type":"integer"},
                    "pageSize":{"type":"integer"}
                  }
                }
                """);
        stubWorkflow(pageQuery);
        setPageActionGraph(pageQuery, "orders", "query", "status", "pageNum", "pageSize");
        RuntimeControlCatalogClient catalogClient = mock(RuntimeControlCatalogClient.class);
        when(catalogClient.getPageAction("qmssmp", "orders", "query")).thenReturn(pageAction(
                "qmssmp", "orders", "query", "READ", false,
                Map.of("type", "object", "properties", Map.of(
                        "status", Map.of("type", "integer", "description", "订单状态：0待付款，1待发货，4已关闭"),
                        "pageNum", Map.of("type", "integer"),
                        "pageSize", Map.of("type", "integer"))), "ACTIVE"));
        List<Map<String, Object>> workflowInputs = new ArrayList<>();
        when(graphExecutor.execute(any(), any(), any(), any(), any())).thenAnswer(invocation -> {
            workflowInputs.add(invocation.getArgument(1));
            return success("当前页面符合条件的订单共有 13 条");
        });
        AgentScopeSupervisorRuntimeAdapter adapter = adapter(model(List.of()), catalogClient);
        List<String> events = new ArrayList<>();

        SupervisorRuntimeAdapter.SupervisorResult result = adapter.execute(
                new SupervisorRuntimeAdapter.SupervisorRequest(
                        agent(), config(false), List.of(pageQuery), Map.of(
                        "message", "请帮我查一下已关闭的订单一共有多少条？只读查询，不要修改数据。",
                        "sessionId", "s-rule-first-page", "projectCode", "qmssmp", "pageKey", "orders"),
                        null, (event, data) -> events.add(event)));

        assertTrue(result.success(), String.valueOf(result.metadata()));
        assertEquals("当前页面符合条件的订单共有 13 条", result.answer());
        @SuppressWarnings("unchecked")
        Map<String, Object> modelMetadata = (Map<String, Object>) result.metadata().get("model");
        assertEquals("RULE_FIRST_PAGE_QUERY", modelMetadata.get("route"));
        assertEquals(1, result.metadata().get("workflowCallCount"));
        assertTrue(events.stream().noneMatch("message.delta"::equals),
                "rule-first result is returned once as the final response");
        assertEquals(1, workflowInputs.size());
        assertEquals(4, workflowInputs.get(0).get("status"));
        assertEquals(1, workflowInputs.get(0).get("pageNum"));
        assertEquals(10, workflowInputs.get(0).get("pageSize"));
        assertEquals(Map.of("status", 4, "pageNum", 1, "pageSize", 10), workflowInputs.get(0).get("params"));
        assertEquals(Map.of("status", 4, "pageNum", 1, "pageSize", 10), workflowInputs.get(0).get("input"));
    }

    @Test
    void ruleFirstPageQueryPreservesExplicitPagination() {
        RuntimeAgentWorkflowToolEntity pageQuery = pageActionTool("wf-page-orders", "mall_order_page_query");
        stubWorkflow(pageQuery);
        setPageActionGraph(pageQuery, "orders", "query", "pageNum", "pageSize");
        RuntimeControlCatalogClient catalogClient = mock(RuntimeControlCatalogClient.class);
        when(catalogClient.getPageAction("qmssmp", "orders", "query")).thenReturn(pageAction(
                "qmssmp", "orders", "query", "READ", false,
                Map.of("type", "object", "properties", Map.of(
                        "pageNum", Map.of("type", "integer"),
                        "pageSize", Map.of("type", "integer"))), "ACTIVE"));
        List<Map<String, Object>> workflowInputs = new ArrayList<>();
        when(graphExecutor.execute(any(), any(), any(), any(), any())).thenAnswer(invocation -> {
            workflowInputs.add(invocation.getArgument(1));
            return success("分页查询完成");
        });
        AgentScopeSupervisorRuntimeAdapter adapter = adapter(model(List.of()), catalogClient);

        SupervisorRuntimeAdapter.SupervisorResult result = adapter.execute(
                new SupervisorRuntimeAdapter.SupervisorRequest(
                        agent(), config(false), List.of(pageQuery), Map.of(
                        "message", "查询第 2 页订单，每页 25 条",
                        "sessionId", "s-page-two", "projectCode", "qmssmp", "pageKey", "orders")));

        assertTrue(result.success(), result.code() + " " + result.answer() + " " + result.metadata());
        assertEquals(2, workflowInputs.get(0).get("pageNum"));
        assertEquals(25, workflowInputs.get(0).get("pageSize"));
    }

    @Test
    void ruleFirstPageQueryKeepsNaturalLanguageSeparateWhenNoStructuredArgumentsExist() {
        RuntimeAgentWorkflowToolEntity pageQuery = pageActionTool("wf-page-orders", "mall_order_page_query");
        stubWorkflow(pageQuery);
        setPageActionGraph(pageQuery, "orders", "query");
        RuntimeControlCatalogClient catalogClient = mock(RuntimeControlCatalogClient.class);
        when(catalogClient.getPageAction("qmssmp", "orders", "query")).thenReturn(pageAction(
                "qmssmp", "orders", "query", "READ", false,
                Map.of("type", "object", "properties", Map.of()), "ACTIVE"));
        List<Map<String, Object>> workflowInputs = new ArrayList<>();
        when(graphExecutor.execute(any(), any(), any(), any(), any())).thenAnswer(invocation -> {
            workflowInputs.add(invocation.getArgument(1));
            return success("查询完成");
        });
        AgentScopeSupervisorRuntimeAdapter adapter = adapter(model(List.of()), catalogClient);

        SupervisorRuntimeAdapter.SupervisorResult result = adapter.execute(
                new SupervisorRuntimeAdapter.SupervisorRequest(
                        agent(), config(false), List.of(pageQuery), Map.of(
                        "message", "查询当前页面的记录", "sessionId", "s-natural-input",
                        "projectCode", "qmssmp", "pageKey", "orders")));

        assertTrue(result.success(), result.code() + " " + result.answer() + " " + result.metadata());
        assertEquals(1, workflowInputs.size());
        assertEquals("查询当前页面的记录", workflowInputs.get(0).get("userInput"));
        assertEquals("查询当前页面的记录", workflowInputs.get(0).get("message"));
        assertEquals("查询当前页面的记录", workflowInputs.get(0).get("input"));
        assertEquals(Map.of(), workflowInputs.get(0).get("params"));
    }

    @Test
    void ruleFirstPageQueryDoesNotRunAWorkflowForAnotherCurrentPage() {
        RuntimeAgentWorkflowToolEntity pageQuery = pageActionTool("wf-page-orders", "mall_order_page_query");
        stubWorkflow(pageQuery);
        setPageActionGraph(pageQuery, "orders", "query");
        RuntimeControlCatalogClient catalogClient = mock(RuntimeControlCatalogClient.class);
        AgentScopeSupervisorRuntimeAdapter adapter = adapter(model(List.of(text("当前页面没有可直接执行的查询动作"))), catalogClient);

        SupervisorRuntimeAdapter.SupervisorResult result = adapter.execute(
                new SupervisorRuntimeAdapter.SupervisorRequest(
                        agent(), config(false), List.of(pageQuery), Map.of(
                        "message", "查询当前页面有多少条记录", "sessionId", "s-wrong-page",
                        "projectCode", "qmssmp", "pageKey", "customers")));

        assertTrue(result.success());
        assertEquals("当前页面没有可直接执行的查询动作", result.answer());
        assertEquals(0, result.metadata().get("workflowCallCount"));
        verifyNoInteractions(catalogClient, graphExecutor);
    }

    @Test
    void ruleFirstPageQueryDoesNotSilentlyDropUnsupportedFilters() {
        RuntimeAgentWorkflowToolEntity pageQuery = pageActionTool("wf-page-orders", "mall_order_page_query");
        stubWorkflow(pageQuery);
        setPageActionGraph(pageQuery, "orders", "query");
        RuntimeControlCatalogClient catalogClient = mock(RuntimeControlCatalogClient.class);
        when(catalogClient.getPageAction("qmssmp", "orders", "query")).thenReturn(pageAction(
                "qmssmp", "orders", "query", "READ", false,
                Map.of("type", "object", "properties", Map.of(
                        "orderSn", Map.of("type", "string", "description", "订单编号"),
                        "pageNum", Map.of("type", "integer"),
                        "pageSize", Map.of("type", "integer"))), "ACTIVE"));
        AgentScopeSupervisorRuntimeAdapter adapter = adapter(model(List.of(text("请确认完整订单编号后再查询"))), catalogClient);

        SupervisorRuntimeAdapter.SupervisorResult result = adapter.execute(
                new SupervisorRuntimeAdapter.SupervisorRequest(
                        agent(), config(false), List.of(pageQuery), Map.of(
                        "message", "查询订单号 A001", "sessionId", "s-filter",
                        "projectCode", "qmssmp", "pageKey", "orders")));

        assertTrue(result.success());
        assertEquals("请确认完整订单编号后再查询", result.answer());
        assertEquals(0, result.metadata().get("workflowCallCount"));
        verifyNoInteractions(graphExecutor);
    }

    @Test
    void ruleFirstPageQueryFallsBackForOverflowingIntegerFilterWithoutDescription() {
        RuntimeAgentWorkflowToolEntity pageQuery = pageActionTool("wf-page-orders", "mall_order_page_query");
        stubWorkflow(pageQuery);
        setPageActionGraph(pageQuery, "orders", "query", "status", "pageNum", "pageSize");
        RuntimeControlCatalogClient catalogClient = mock(RuntimeControlCatalogClient.class);
        when(catalogClient.getPageAction("qmssmp", "orders", "query")).thenReturn(pageAction(
                "qmssmp", "orders", "query", "READ", false,
                Map.of("type", "object", "properties", Map.of(
                        "status", Map.of("type", "integer"),
                        "pageNum", Map.of("type", "integer"),
                        "pageSize", Map.of("type", "integer"))), "ACTIVE"));
        AgentScopeSupervisorRuntimeAdapter adapter = adapter(
                model(List.of(text("状态值超出可直接解析范围，请重新确认"))), catalogClient);

        SupervisorRuntimeAdapter.SupervisorResult result = adapter.execute(
                new SupervisorRuntimeAdapter.SupervisorRequest(
                        agent(), config(false), List.of(pageQuery), Map.of(
                        "message", "查询状态 999999999999999999999999999999 的订单",
                        "sessionId", "s-overflow-filter", "projectCode", "qmssmp", "pageKey", "orders")));

        assertTrue(result.success());
        assertEquals("状态值超出可直接解析范围，请重新确认", result.answer());
        assertEquals(0, result.metadata().get("workflowCallCount"));
        verifyNoInteractions(graphExecutor);
    }

    @Test
    void ruleFirstPageQueryDoesNotTreatOneNegatedWriteAsAReadOnlyCompoundRequest() {
        RuntimeAgentWorkflowToolEntity pageQuery = pageActionTool("wf-page-orders", "mall_order_page_query");
        stubWorkflow(pageQuery);
        setPageActionGraph(pageQuery, "orders", "query", "pageNum", "pageSize");
        RuntimeControlCatalogClient catalogClient = mock(RuntimeControlCatalogClient.class);
        AgentScopeSupervisorRuntimeAdapter adapter = adapter(
                model(List.of(text("复合写操作未走只读快捷路径"))), catalogClient);

        SupervisorRuntimeAdapter.SupervisorResult result = adapter.execute(
                new SupervisorRuntimeAdapter.SupervisorRequest(
                        agent(), config(false), List.of(pageQuery), Map.of(
                        "message", "查询订单，不要删除 A，但删除 B",
                        "sessionId", "s-compound-write", "projectCode", "qmssmp", "pageKey", "orders")));

        assertTrue(result.success());
        assertEquals("复合写操作未走只读快捷路径", result.answer());
        assertEquals(0, result.metadata().get("workflowCallCount"));
        verifyNoInteractions(catalogClient, graphExecutor);
    }

    @Test
    void ruleFirstPageQueryDoesNotDropEnglishWriteIntent() {
        RuntimeAgentWorkflowToolEntity pageQuery = pageActionTool("wf-page-orders", "mall_order_page_query");
        stubWorkflow(pageQuery);
        setPageActionGraph(pageQuery, "orders", "query", "pageNum", "pageSize");
        RuntimeControlCatalogClient catalogClient = mock(RuntimeControlCatalogClient.class);
        AgentScopeSupervisorRuntimeAdapter adapter = adapter(
                model(List.of(text("English compound request used Supervisor"))), catalogClient);

        SupervisorRuntimeAdapter.SupervisorResult result = adapter.execute(
                new SupervisorRuntimeAdapter.SupervisorRequest(
                        agent(), config(false), List.of(pageQuery), Map.of(
                        "message", "query the orders and then delete one",
                        "sessionId", "s-english-write", "projectCode", "qmssmp", "pageKey", "orders")));

        assertTrue(result.success());
        assertEquals("English compound request used Supervisor", result.answer());
        assertEquals(0, result.metadata().get("workflowCallCount"));
        verifyNoInteractions(catalogClient, graphExecutor);
    }

    @Test
    void ruleFirstPageQueryRejectsUnsafeCatalogAction() {
        RuntimeAgentWorkflowToolEntity pageQuery = pageActionTool("wf-page-orders", "mall_order_page_query");
        stubWorkflow(pageQuery);
        setPageActionGraph(pageQuery, "orders", "query");
        RuntimeControlCatalogClient catalogClient = mock(RuntimeControlCatalogClient.class);
        when(catalogClient.getPageAction("qmssmp", "orders", "query")).thenReturn(pageAction(
                "qmssmp", "orders", "query", "WRITE", true,
                Map.of("type", "object", "properties", Map.of()), "ACTIVE"));
        AgentScopeSupervisorRuntimeAdapter adapter = adapter(model(List.of(text("该动作需要确认，未自动执行"))), catalogClient);

        SupervisorRuntimeAdapter.SupervisorResult result = adapter.execute(
                new SupervisorRuntimeAdapter.SupervisorRequest(
                        agent(), config(false), List.of(pageQuery), Map.of(
                        "message", "查询当前页面有多少条记录", "sessionId", "s-unsafe",
                        "projectCode", "qmssmp", "pageKey", "orders")));

        assertTrue(result.success());
        assertEquals("该动作需要确认，未自动执行", result.answer());
        assertEquals(0, result.metadata().get("workflowCallCount"));
        verifyNoInteractions(graphExecutor);
    }

    @Test
    void ruleFirstPageQueryRejectsMisdeclaredReadActionWithWriteSemantics() {
        RuntimeAgentWorkflowToolEntity pageQuery = pageActionTool("wf-page-orders", "mall_order_page_query");
        stubWorkflow(pageQuery);
        setPageActionGraph(pageQuery, "orders", "queryAndDelete");
        RuntimeControlCatalogClient catalogClient = mock(RuntimeControlCatalogClient.class);
        when(catalogClient.getPageAction("qmssmp", "orders", "queryAndDelete")).thenReturn(pageAction(
                "qmssmp", "orders", "queryAndDelete", "READ", false,
                Map.of("type", "object", "properties", Map.of()), "ACTIVE"));
        AgentScopeSupervisorRuntimeAdapter adapter = adapter(
                model(List.of(text("写语义动作未走只读快捷路径"))), catalogClient);

        SupervisorRuntimeAdapter.SupervisorResult result = adapter.execute(
                new SupervisorRuntimeAdapter.SupervisorRequest(
                        agent(), config(false), List.of(pageQuery), Map.of(
                        "message", "查询当前页面有多少条记录", "sessionId", "s-misdeclared-write",
                        "projectCode", "qmssmp", "pageKey", "orders")));

        assertTrue(result.success());
        assertEquals("写语义动作未走只读快捷路径", result.answer());
        assertEquals(0, result.metadata().get("workflowCallCount"));
        verifyNoInteractions(graphExecutor);
    }

    @Test
    void ruleFirstPageQuerySummarizesOnlySafeStructuredCount() {
        RuntimeAgentWorkflowToolEntity pageQuery = pageActionTool("wf-page-orders", "mall_order_page_query");
        stubWorkflow(pageQuery);
        setPageActionGraph(pageQuery, "orders", "query", "pageNum", "pageSize");
        RuntimeControlCatalogClient catalogClient = mock(RuntimeControlCatalogClient.class);
        when(catalogClient.getPageAction("qmssmp", "orders", "query")).thenReturn(pageAction(
                "qmssmp", "orders", "query", "READ", false,
                Map.of("type", "object", "properties", Map.of(
                        "pageNum", Map.of("type", "integer"),
                        "pageSize", Map.of("type", "integer"))), "ACTIVE"));
        when(graphExecutor.execute(any(), any(), any(), any(), any())).thenReturn(successWithStructuredCount(13));
        AgentScopeSupervisorRuntimeAdapter adapter = adapter(model(List.of()), catalogClient);

        SupervisorRuntimeAdapter.SupervisorResult result = adapter.execute(
                new SupervisorRuntimeAdapter.SupervisorRequest(
                        agent(), config(false), List.of(pageQuery), Map.of(
                        "message", "查询当前页面有多少条记录", "sessionId", "s-summary",
                        "projectCode", "qmssmp", "pageKey", "orders")));

        assertTrue(result.success());
        assertEquals("执行成功，共 13 条", result.answer());
    }

    @Test
    void ruleFirstPageQueryExplicitlyReportsNoMatchingRecords() {
        RuntimeAgentWorkflowToolEntity pageQuery = pageActionTool("wf-page-orders", "mall_order_page_query");
        stubWorkflow(pageQuery);
        setPageActionGraph(pageQuery, "orders", "query", "pageNum", "pageSize");
        RuntimeControlCatalogClient catalogClient = mock(RuntimeControlCatalogClient.class);
        when(catalogClient.getPageAction("qmssmp", "orders", "query")).thenReturn(pageAction(
                "qmssmp", "orders", "query", "READ", false,
                Map.of("type", "object", "properties", Map.of(
                        "pageNum", Map.of("type", "integer"),
                        "pageSize", Map.of("type", "integer"))), "ACTIVE"));
        when(graphExecutor.execute(any(), any(), any(), any(), any())).thenReturn(successWithPageActionSummary(0));
        AgentScopeSupervisorRuntimeAdapter adapter = adapter(model(List.of()), catalogClient);

        SupervisorRuntimeAdapter.SupervisorResult result = adapter.execute(
                new SupervisorRuntimeAdapter.SupervisorRequest(
                        agent(), config(false), List.of(pageQuery), Map.of(
                        "message", "查询当前页面有多少条记录", "sessionId", "s-empty-summary",
                        "projectCode", "qmssmp", "pageKey", "orders")));

        assertTrue(result.success());
        assertEquals("页面查询已完成，未查询到匹配记录。", result.answer());
    }

    @Test
    void ruleFirstPageQueryRejectsCompositeWorkflowWithAnotherExecutableNode() {
        RuntimeAgentWorkflowToolEntity pageQuery = pageActionTool("wf-page-orders", "mall_order_page_query");
        stubWorkflow(pageQuery);
        RuntimeWorkflowVersionEntity version = workflowVersions.get(pageQuery.getWorkflowVersionId());
        version.setGraphSpecSnapshotJson("""
                {"nodes":[
                  {"id":"page-action","type":"PAGE_ACTION","config":{"pageKey":"orders","actionKey":"query","inputMapping":{}}},
                  {"id":"write-api","type":"TOOL","config":{"qualifiedName":"orders:update"}},
                  {"id":"answer","type":"ANSWER","config":{"template":"done"}}
                ],"edges":[]}
                """);
        RuntimeControlCatalogClient catalogClient = mock(RuntimeControlCatalogClient.class);
        AgentScopeSupervisorRuntimeAdapter adapter = adapter(model(List.of(text("复合工作流未自动执行"))), catalogClient);

        SupervisorRuntimeAdapter.SupervisorResult result = adapter.execute(
                new SupervisorRuntimeAdapter.SupervisorRequest(
                        agent(), config(false), List.of(pageQuery), Map.of(
                        "message", "查询当前页面有多少条记录", "sessionId", "s-composite",
                        "projectCode", "qmssmp", "pageKey", "orders")));

        assertTrue(result.success());
        assertEquals("复合工作流未自动执行", result.answer());
        assertEquals(0, result.metadata().get("workflowCallCount"));
        verifyNoInteractions(catalogClient, graphExecutor);
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
    void modelToolArgumentsCannotOverrideSignedExecutionContext() throws Exception {
        RuntimeAgentWorkflowToolEntity teamTool = tool("wf-team", "query_team");
        stubWorkflow(teamTool);
        List<Map<String, Object>> workflowInputs = new ArrayList<>();
        when(graphExecutor.execute(any(), any(), any(), any(), any())).thenAnswer(invocation -> {
            workflowInputs.add(invocation.getArgument(1));
            return success("ok");
        });
        AgentScopeSupervisorRuntimeAdapter adapter = adapter(model(List.of(
                calls(call("plan-secure", "record_supervisor_plan", Map.of(
                        "summary", "查询班组", "steps", List.of("查询班组"),
                        "workflowToolNames", List.of("query_team")))),
                calls(call("workflow-secure", "query_team", Map.of(
                        "kind", "team",
                        "projectCode", "attacker-project",
                        "tenantId", "attacker-tenant",
                        "roles", List.of("admin"),
                        "sessionId", "attacker-session",
                        "pageInstanceId", "attacker-page",
                        "origin", "https://attacker.example",
                        "metadata", Map.of("roles", List.of("admin"))))),
                text("查询完成"),
                text("查询完成"))));

        SupervisorRuntimeAdapter.SupervisorResult result = adapter.execute(
                new SupervisorRuntimeAdapter.SupervisorRequest(
                        agent(), config(false), List.of(teamTool), Map.of(
                        "message", "查询班组", "sessionId", "trusted-session",
                        "projectCode", "qmssmp", "tenantId", "tenant-a",
                        "roles", List.of("reader"), "pageInstanceId", "trusted-page",
                        "origin", "https://mall.example")));

        assertTrue(result.success());
        assertEquals(1, workflowInputs.size());
        Map<String, Object> workflowInput = workflowInputs.get(0);
        assertEquals("qmssmp", workflowInput.get("projectCode"));
        assertEquals("tenant-a", workflowInput.get("tenantId"));
        assertEquals(List.of("reader"), workflowInput.get("roles"));
        assertEquals("trusted-session", workflowInput.get("sessionId"));
        assertEquals("trusted-page", workflowInput.get("pageInstanceId"));
        assertEquals("https://mall.example", workflowInput.get("origin"));
        assertEquals("team", workflowInput.get("kind"));
        @SuppressWarnings("unchecked")
        Map<String, Object> structuredArgs = (Map<String, Object>) workflowInput.get("params");
        assertEquals(Map.of("kind", "team"), structuredArgs);
        assertEquals(structuredArgs, workflowInput.get("input"));
    }

    @Test
    void surfacesNonBlockingWorkflowPresentationWithFinalAgentAnswer() throws Exception {
        RuntimeAgentWorkflowToolEntity tool = tool("wf-team", "query_team");
        stubWorkflow(tool);
        Map<String, Object> uiRequest = Map.of(
                "schemaVersion", "1.0",
                "type", "PRESENT_OUTPUT",
                "component", "list_card",
                "presentation", Map.of("mode", "card_only"),
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

        List<String> events = new ArrayList<>();
        SupervisorRuntimeAdapter.SupervisorResult result = adapter.execute(
                new SupervisorRuntimeAdapter.SupervisorRequest(
                        agent(), config(false), List.of(tool), Map.of(
                        "message", "查询一班", "sessionId", "s-list-card", "projectCode", "qmssmp"),
                        null, (event, data) -> events.add(event)));

        assertTrue(result.success(), String.valueOf(result.metadata()));
        assertEquals(uiRequest, result.uiRequest());
        assertEquals("已展示班组查询结果", result.answer());
        assertFalse(events.contains("message.delta"));
        assertEquals("card_only", result.metadata().get("presentationMode"));
        assertEquals(true, result.metadata().get("answerTextSuppressed"));
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
    void neverRetriesTheSameSideEffectWorkflowAfterAnAmbiguousFailure() throws Exception {
        RuntimeAgentWorkflowToolEntity tool = pageActionTool("wf-team-toggle", "toggle_team_on_page");
        stubWorkflow(tool);
        AtomicInteger execution = new AtomicInteger();
        when(graphExecutor.execute(any(), any(), any(), any(), any())).thenAnswer(invocation -> {
            execution.incrementAndGet();
            return new RuntimeGraphSpecExecutionResult(
                    false,
                    "PAGE_BRIDGE_ACTION_FAILED",
                    "Page Bridge action execution timed out after it was claimed",
                    null,
                    "PAGE_ACTION",
                    List.of(),
                    Map.of("pageBridgeStatus", "TIMEOUT"));
        });
        AgentScopeSupervisorRuntimeAdapter adapter = adapter(model(List.of(
                calls(call("plan-write", "record_supervisor_plan", Map.of(
                        "summary", "Disable the team from the page",
                        "steps", List.of("Run the page action"),
                        "workflowToolNames", List.of("toggle_team_on_page")))),
                calls(call("write-1", "toggle_team_on_page", Map.of("enabled", false))),
                calls(call("retry-plan", "record_supervisor_plan", Map.of(
                        "summary", "Retry the timed out page action",
                        "steps", List.of("Retry the page action"),
                        "workflowToolNames", List.of("toggle_team_on_page"),
                        "reason", "timeout"))),
                calls(call("abandon-plan", "record_supervisor_plan", Map.of(
                        "summary", "Stop because the write outcome is ambiguous",
                        "steps", List.of("Explain that the action was not retried"),
                        "workflowToolNames", List.of(),
                        "reason", "side-effect Workflow is non-retryable"))),
                calls(call("final-after-write-failure", "begin_final_answer", Map.of())),
                text("The page action timed out and was not retried automatically."),
                text("The page action timed out and was not retried automatically."))));

        SupervisorRuntimeAdapter.SupervisorResult result = adapter.execute(
                new SupervisorRuntimeAdapter.SupervisorRequest(
                        agent(), config(false), List.of(tool), Map.of(
                        "message", "请在当前页面停用班组",
                        "sessionId", "s-side-effect-timeout",
                        "projectCode", "qmssmp")));

        assertTrue(result.success(), String.valueOf(result.metadata()));
        assertEquals(1, execution.get());
        assertEquals(1, result.metadata().get("workflowCallCount"));
        assertEquals(1, result.metadata().get("replanCount"));
    }

    @Test
    void replansToReadOnlyWorkflowAfterPageActionPolicyDenial() throws Exception {
        RuntimeAgentWorkflowToolEntity pageTool = pageActionTool("wf-page-team", "query_team_on_page");
        RuntimeAgentWorkflowToolEntity apiTool = tool("wf-api-team", "query_team_by_api");
        stubWorkflow(pageTool);
        stubWorkflow(apiTool);
        when(graphExecutor.execute(any(), any(), any(), any(), any()))
                .thenReturn(success("找到建设一工班"));
        AgentScopeSupervisorRuntimeAdapter adapter = adapter(model(List.of(
                calls(call("plan-page", "record_supervisor_plan", Map.of(
                        "summary", "尝试在页面查询", "steps", List.of("在页面查询班组"),
                        "workflowToolNames", List.of("query_team_on_page")))),
                calls(call("page-denied", "query_team_on_page", Map.of("teamName", "建设一工班"))),
                calls(call("replan-api", "record_supervisor_plan", Map.of(
                        "summary", "改用只读接口查询", "steps", List.of("调用接口查询班组"),
                        "workflowToolNames", List.of("query_team_by_api"), "reason", "页面操作未获授权"))),
                calls(call("api-query", "query_team_by_api", Map.of("teamName", "建设一工班"))),
                text("已通过接口找到建设一工班"),
                text("已通过接口找到建设一工班"))));

        SupervisorRuntimeAdapter.SupervisorResult result = adapter.execute(
                new SupervisorRuntimeAdapter.SupervisorRequest(
                        agent(), config(false), List.of(pageTool, apiTool), Map.of(
                        "message", "再查询一下建设一工班", "sessionId", "s-policy-replan",
                        "projectCode", "qmssmp")));

        assertTrue(result.success(), String.valueOf(result.metadata()));
        assertEquals("已通过接口找到建设一工班", result.answer());
        assertEquals(2, result.metadata().get("planCount"));
        assertEquals(1, result.metadata().get("replanCount"));
        assertEquals(1, result.metadata().get("workflowCallCount"));
        assertEquals(1, result.metadata().get("guardDenyCount"));
    }

    @Test
    void abandonsFailedWorkflowPlanBeforeReturningAUserFacingExplanation() throws Exception {
        RuntimeAgentWorkflowToolEntity pageTool = pageActionTool("wf-page-team", "query_team_on_page");
        stubWorkflow(pageTool);
        AgentScopeSupervisorRuntimeAdapter adapter = adapter(model(List.of(
                calls(call("plan-page", "record_supervisor_plan", Map.of(
                        "summary", "尝试在页面查询", "steps", List.of("在页面查询班组"),
                        "workflowToolNames", List.of("query_team_on_page")))),
                calls(call("page-denied", "query_team_on_page", Map.of("teamName", "建设一工班"))),
                calls(call("replan-abandon", "record_supervisor_plan", Map.of(
                        "summary", "页面操作未获授权，安全结束",
                        "steps", List.of("向用户说明未执行"),
                        "workflowToolNames", List.of(), "reason", "没有可安全继续的 Workflow"))),
                calls(call("final-after-denial", "begin_final_answer", Map.of())),
                text("页面操作未执行；请明确要求在页面上操作后重试。"),
                text("页面操作未执行；请明确要求在页面上操作后重试。"))));

        SupervisorRuntimeAdapter.SupervisorResult result = adapter.execute(
                new SupervisorRuntimeAdapter.SupervisorRequest(
                        agent(), config(false), List.of(pageTool), Map.of(
                        "message", "再查询一下建设一工班", "sessionId", "s-policy-abandon",
                        "projectCode", "qmssmp")));

        assertTrue(result.success(), String.valueOf(result.metadata()));
        assertEquals("页面操作未执行；请明确要求在页面上操作后重试。", result.answer());
        assertEquals(2, result.metadata().get("planCount"));
        assertEquals(1, result.metadata().get("replanCount"));
        assertEquals(0, result.metadata().get("workflowCallCount"));
        assertEquals(1, result.metadata().get("guardDenyCount"));
        assertEquals(List.of(), result.metadata().get("plannedWorkflowToolNames"));
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

    @Test
    void persistsLastStepContinuationAsItsOwnTurn() {
        RuntimeSessionMemoryService memoryService = mock(RuntimeSessionMemoryService.class);
        com.enterprise.ai.runtime.memory.RuntimeSessionMemoryKey memoryKey =
                new com.enterprise.ai.runtime.memory.RuntimeSessionMemoryKey(
                        false, "default", null, "agent-1", "s-resume",
                        null, null, "UNTRUSTED");
        org.mockito.Mockito.doReturn("lease-1").when(memoryService)
                .acquireTurn(any(), any(), any());
        when(memoryService.resolve(any(), any(), any())).thenReturn(memoryKey);
        AgentScopeSupervisorRuntimeAdapter adapter = adapter(model(List.of()), null, memoryService);
        Map<String, Object> continuation = new LinkedHashMap<>();
        continuation.put("continuationSchemaVersion", 2);
        continuation.put("agentId", "agent-1");
        continuation.put("agentConfigVersionId", 7L);
        continuation.put("traceId", "trace-1");
        continuation.put("waitingToolName", "query_team");
        continuation.put("plannedWorkflowToolNames", List.of("query_team"));
        continuation.put("plannedWorkflowCursor", 0);
        continuation.put("completedWorkflowToolNames", List.of());
        continuation.put("recordedPlan", Map.of(
                "summary", "query",
                "steps", List.of("query"),
                "workflowToolNames", List.of("query_team"),
                "planNo", 1));
        RuntimeAgentWorkflowToolEntity queryTool = tool("wf-query", "query_team");
        stubWorkflow(queryTool);

        SupervisorRuntimeAdapter.SupervisorResult result = adapter.continueAfterWorkflowInteraction(
                continuation,
                Map.of("success", true, "status", "COMPLETED", "code", "OK", "answer", "完成"),
                new SupervisorRuntimeAdapter.SupervisorRequest(
                        agent(), config(false), List.of(queryTool), Map.of(
                        "sessionId", "s-resume", "interactionId", "wfi_1",
                        "uiSubmit", Map.of("action", "confirm"))));

        assertTrue(result.success(), result.code() + " " + result.answer() + " " + result.metadata());
        verify(memoryService).recordTurn(any(), any(), any(),
                org.mockito.ArgumentMatchers.eq("trace-1"),
                org.mockito.ArgumentMatchers.eq("wfi_1"),
                org.mockito.ArgumentMatchers.eq("Interaction response: confirm"),
                org.mockito.ArgumentMatchers.eq("完成"),
                org.mockito.ArgumentMatchers.eq("SUPERVISOR_COMPLETED"),
                org.mockito.ArgumentMatchers.eq(true),
                org.mockito.ArgumentMatchers.eq(false),
                org.mockito.ArgumentMatchers.eq(false),
                org.mockito.ArgumentMatchers.eq("lease-1"));
    }

    private AgentScopeSupervisorRuntimeAdapter adapter(RuntimeModelServiceClient modelClient) {
        return adapter(modelClient, null);
    }

    private AgentScopeSupervisorRuntimeAdapter adapter(RuntimeModelServiceClient modelClient,
                                                       RuntimeControlCatalogClient controlCatalogClient) {
        return adapter(modelClient, controlCatalogClient, null);
    }

    private AgentScopeSupervisorRuntimeAdapter adapter(RuntimeModelServiceClient modelClient,
                                                       RuntimeControlCatalogClient controlCatalogClient,
                                                       RuntimeSessionMemoryService memoryService) {
        SupervisorExecutionTraceService.TraceHandle handle =
                new SupervisorExecutionTraceService.TraceHandle("trace-1", "span-1", 1L, LocalDateTime.now());
        when(traceService.begin(any(), any(), any(), any())).thenReturn(handle);
        when(traceService.begin(any(), any(), any(), any(), any())).thenReturn(handle);
        when(traceService.beginOrResume(any(), any(), any(), any())).thenReturn(handle);
        when(traceService.beginOrResume(any(), any(), any(), any(), any())).thenReturn(handle);
        when(traceService.resume(any())).thenReturn(handle);
        SupervisorToolPolicyService policy = new SupervisorToolPolicyService(
                traceService, mock(SupervisorApprovalInteractionService.class), objectMapper);
        if (memoryService != null) {
            return new AgentScopeSupervisorRuntimeAdapter(
                    modelClient,
                    null,
                    controlCatalogClient,
                    workflowMapper,
                    versionMapper,
                    graphExecutor,
                    mock(com.enterprise.ai.runtime.execution.RuntimeWorkflowInteractionSessionService.class),
                    memoryService,
                    policy,
                    traceService,
                    objectMapper);
        }
        return new AgentScopeSupervisorRuntimeAdapter(
                modelClient,
                null,
                controlCatalogClient,
                workflowMapper,
                versionMapper,
                graphExecutor,
                mock(com.enterprise.ai.runtime.execution.RuntimeWorkflowInteractionSessionService.class),
                new RuntimeChatMemoryStore(20),
                policy,
                traceService,
                objectMapper);
    }

    private AgentScopeSupervisorRuntimeAdapter adapterWithContextEngineering(
            RuntimeModelServiceClient modelClient,
            RuntimeSessionMemoryService memoryService,
            RuntimeContextEngineeringService contextEngineeringService) {
        SupervisorExecutionTraceService.TraceHandle handle =
                new SupervisorExecutionTraceService.TraceHandle(
                        "trace-1", "span-1", 1L, LocalDateTime.now());
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
                null,
                workflowMapper,
                versionMapper,
                graphExecutor,
                mock(com.enterprise.ai.runtime.execution.RuntimeWorkflowInteractionSessionService.class),
                memoryService,
                policy,
                traceService,
                objectMapper,
                contextEngineeringService);
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

    private void setPageActionGraph(RuntimeAgentWorkflowToolEntity tool,
                                    String pageKey,
                                    String actionKey,
                                    String... inputNames) {
        RuntimeWorkflowVersionEntity version = workflowVersions.get(tool.getWorkflowVersionId());
        Map<String, Object> inputMapping = new LinkedHashMap<>();
        for (String inputName : inputNames) {
            inputMapping.put(inputName, "params." + inputName);
        }
        try {
            version.setGraphSpecSnapshotJson(objectMapper.writeValueAsString(Map.of(
                    "nodes", List.of(Map.of(
                            "id", "page-action",
                            "type", "PAGE_ACTION",
                            "config", Map.of(
                                    "pageKey", pageKey,
                                    "actionKey", actionKey,
                                    "inputMapping", inputMapping))),
                    "edges", List.of())));
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private RuntimeControlCatalogClient.PageActionCatalogEntry pageAction(
            String projectCode,
            String pageKey,
            String actionKey,
            String riskLevel,
            boolean confirmRequired,
            Object inputSchema,
            String status) {
        return new RuntimeControlCatalogClient.PageActionCatalogEntry(
                1L, projectCode, pageKey, actionKey, "Query", "Read page data",
                riskLevel, confirmRequired, null, inputSchema, Map.of(), Map.of(),
                List.of(), null, Map.of(), status);
    }

    private RuntimeGraphSpecExecutionResult success(String answer) {
        return new RuntimeGraphSpecExecutionResult(true, "OK", answer,
                null, null, new ArrayList<>(), Map.of());
    }

    private RuntimeGraphSpecExecutionResult successWithStructuredCount(int total) {
        return new RuntimeGraphSpecExecutionResult(true, "OK", "{message=执行成功, data={total=" + total + "}}",
                null, null, new ArrayList<>(), Map.of(), Map.of(
                "previousOutput", Map.of("message", "执行成功", "data", Map.of("total", total))));
    }

    private RuntimeGraphSpecExecutionResult successWithPageActionSummary(int total) {
        return new RuntimeGraphSpecExecutionResult(true, "OK", "页面动作已完成",
                null, null, new ArrayList<>(), Map.of(), Map.of(
                "pageActionResults", List.of(Map.of(
                        "actionKey", "query", "success", true, "status", "SUCCESS",
                        "total", total, "empty", total == 0))));
    }
}
