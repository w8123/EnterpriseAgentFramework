package com.enterprise.ai.agent.runtime.host;




import com.enterprise.ai.agent.runtime.AgentRuntimeAdapter;
import com.enterprise.ai.agent.runtime.AgentRuntimeResult;
import com.enterprise.ai.agent.runtime.AgentRuntimeRequest;
import com.enterprise.ai.agent.client.ModelServiceClient;
import com.enterprise.ai.agent.client.SkillsServiceClient;
import com.enterprise.ai.agent.graph.GraphSpec;
import com.enterprise.ai.agent.runtime.GraphRuntimeContext;
import com.enterprise.ai.agent.skill.interactive.SkillInteractionEntity;
import com.enterprise.ai.agent.skill.interactive.SkillInteractionMapper;
import com.enterprise.ai.agent.tool.log.ToolCallLogService;
import com.enterprise.ai.agent.capability.catalog.tool.definition.ToolDefinitionService;
import com.enterprise.ai.agent.trace.AgentTraceSpanService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.argThat;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LangGraph4jRuntimeAdapterTest {

    @Test
    void executesUserInputNodeAndExposesParamsVariables() {
        LangGraph4jRuntimeAdapter adapter = adapter(null);

        AgentRuntimeResult result = adapter.execute(AgentRuntimeRequest.builder()
                .traceId("trace-user-input")
                .sessionId("session-user-input")
                .message("fallback question")
                .runtimeOptions(Map.of("params", Map.of(
                        "question", "How do I transfer?",
                        "upload_document", "file-001")))
                .graphSpec(GraphSpec.builder()
                                .code("user_input_graph")
                                .entry("user_input")
                                .finishNode("answer")
                                .node(GraphSpec.Node.builder()
                                        .id("user_input")
                                        .type("USER_INPUT")
                                        .config(Map.of(
                                                "outputAlias", "params",
                                                "fields", List.of(
                                                        Map.of("name", "question", "type", "string", "required", true),
                                                        Map.of("name", "upload_document", "type", "file", "required", true))))
                                        .build())
                                .node(GraphSpec.Node.builder()
                                        .id("answer")
                                        .type("ANSWER")
                                        .config(Map.of("template", "{{ params.question }} / {{ params.upload_document }}"))
                                        .build())
                                .edge(GraphSpec.Edge.builder().from("START").to("user_input").condition("always").build())
                                .edge(GraphSpec.Edge.builder().from("user_input").to("answer").condition("always").build())
                                .edge(GraphSpec.Edge.builder().from("answer").to("END").condition("always").build())
                                .build()).graphRuntimeContext(GraphRuntimeContext.builder().runtimeType(AgentRuntimeAdapter.LANGGRAPH4J_RUNTIME_TYPE).build())
                .build());

        assertTrue(result.isSuccess());
        assertEquals("How do I transfer? / file-001", result.getAnswer());
        assertTrue(result.getMetadata().containsKey("graphNodes"));
    }

    @Test
    void userInputQuestionFallsBackToChatMessage() {
        LangGraph4jRuntimeAdapter adapter = adapter(null);

        AgentRuntimeResult result = adapter.execute(AgentRuntimeRequest.builder()
                .traceId("trace-user-input-message-fallback")
                .sessionId("session-user-input-message-fallback")
                .message("帮我查询负责人为靳圣辉的班组")
                .graphSpec(GraphSpec.builder()
                                .code("user_input_message_fallback_graph")
                                .entry("user_input")
                                .finishNode("answer")
                                .node(GraphSpec.Node.builder()
                                        .id("user_input")
                                        .type("USER_INPUT")
                                        .config(Map.of(
                                                "outputAlias", "params",
                                                "fields", List.of(Map.of(
                                                        "name", "question",
                                                        "type", "string",
                                                        "required", true))))
                                        .build())
                                .node(GraphSpec.Node.builder()
                                        .id("answer")
                                        .type("ANSWER")
                                        .config(Map.of("template", "{{ params.question }}"))
                                        .build())
                                .edge(GraphSpec.Edge.builder().from("START").to("user_input").condition("always").build())
                                .edge(GraphSpec.Edge.builder().from("user_input").to("answer").condition("always").build())
                                .edge(GraphSpec.Edge.builder().from("answer").to("END").condition("always").build())
                                .build())
                .graphRuntimeContext(GraphRuntimeContext.builder().runtimeType(AgentRuntimeAdapter.LANGGRAPH4J_RUNTIME_TYPE).build())
                .build());

        assertTrue(result.isSuccess());
        assertEquals("帮我查询负责人为靳圣辉的班组", result.getAnswer());
    }

    @Test
    void userInputFieldSourceReadsInputMessage() {
        LangGraph4jRuntimeAdapter adapter = adapter(null);

        AgentRuntimeResult result = adapter.execute(AgentRuntimeRequest.builder()
                .traceId("trace-user-input-source")
                .sessionId("session-user-input-source")
                .message("帮我查询负责人为靳圣辉的班组")
                .graphSpec(GraphSpec.builder()
                                .code("user_input_source_graph")
                                .entry("user_input")
                                .finishNode("answer")
                                .node(GraphSpec.Node.builder()
                                        .id("user_input")
                                        .type("USER_INPUT")
                                        .config(Map.of(
                                                "outputAlias", "params",
                                                "fields", List.of(Map.of(
                                                        "name", "question",
                                                        "type", "string",
                                                        "required", true,
                                                        "source", "input.message"))))
                                        .build())
                                .node(GraphSpec.Node.builder()
                                        .id("answer")
                                        .type("ANSWER")
                                        .config(Map.of("template", "{{ params.question }}"))
                                        .build())
                                .edge(GraphSpec.Edge.builder().from("START").to("user_input").condition("always").build())
                                .edge(GraphSpec.Edge.builder().from("user_input").to("answer").condition("always").build())
                                .edge(GraphSpec.Edge.builder().from("answer").to("END").condition("always").build())
                                .build())
                .graphRuntimeContext(GraphRuntimeContext.builder().runtimeType(AgentRuntimeAdapter.LANGGRAPH4J_RUNTIME_TYPE).build())
                .build());

        assertTrue(result.isSuccess());
        assertEquals("帮我查询负责人为靳圣辉的班组", result.getAnswer());
    }

    @Test
    void executesInteractionCollectInputNodeAndExposesStructuredVariables() {
        LangGraph4jRuntimeAdapter adapter = adapter(null);

        AgentRuntimeResult result = adapter.execute(AgentRuntimeRequest.builder()
                .traceId("trace-interaction-input")
                .sessionId("session-interaction-input")
                .message("recommend package")
                .runtimeOptions(Map.of("params", Map.of(
                        "age", 36,
                        "chronicDisease", "hypertension")))
                .graphSpec(GraphSpec.builder()
                                .code("interaction_graph")
                                .entry("collect")
                                .finishNode("answer")
                                .node(GraphSpec.Node.builder()
                                        .id("collect")
                                        .type("INTERACTION")
                                        .config(Map.of(
                                                "interactionType", "COLLECT_INPUT",
                                                "outputAlias", "profile",
                                                "fields", List.of(
                                                        Map.of("key", "age", "type", "integer", "required", true),
                                                        Map.of("key", "chronicDisease", "type", "string", "required", false))))
                                        .build())
                                .node(GraphSpec.Node.builder()
                                        .id("answer")
                                        .type("ANSWER")
                                        .config(Map.of("template", "{{ profile.age }} / {{ profile.chronicDisease }}"))
                                        .build())
                                .edge(GraphSpec.Edge.builder().from("START").to("collect").condition("always").build())
                                .edge(GraphSpec.Edge.builder().from("collect").to("answer").condition("always").build())
                                .edge(GraphSpec.Edge.builder().from("answer").to("END").condition("always").build())
                                .build()).graphRuntimeContext(GraphRuntimeContext.builder().runtimeType(AgentRuntimeAdapter.LANGGRAPH4J_RUNTIME_TYPE).build())
                .build());

        assertTrue(result.isSuccess());
        assertEquals("36 / hypertension", result.getAnswer());
    }

    @Test
    @SuppressWarnings("unchecked")
    void presentOutputNodePublishesUiRequestForToolResultDisplay() {
        LangGraph4jRuntimeAdapter adapter = adapter(null);

        AgentRuntimeResult result = adapter.execute(AgentRuntimeRequest.builder()
                .traceId("trace-present-output")
                .sessionId("session-present-output")
                .message("show teams")
                .runtimeOptions(Map.of("params", Map.of(
                        "toolResult", Map.of("items", List.of(
                                Map.of("teamName", "研发一组", "managerName", "张三"),
                                Map.of("teamName", "研发二组", "managerName", "李四"))))))
                .graphSpec(GraphSpec.builder()
                                .code("present_output_graph")
                                .entry("display")
                                .finishNode("display")
                                .node(GraphSpec.Node.builder()
                                        .id("display")
                                        .type("INTERACTION")
                                        .config(Map.of(
                                                "interactionType", "PRESENT_OUTPUT",
                                                "component", "TABLE",
                                                "title", "班组列表",
                                                "dataExpression", "params.toolResult.items",
                                                "renderSchema", Map.of("columns", List.of(
                                                        Map.of("key", "teamName", "label", "班组"),
                                                        Map.of("key", "managerName", "label", "负责人")))))
                                        .build())
                                .edge(GraphSpec.Edge.builder().from("START").to("display").condition("always").build())
                                .edge(GraphSpec.Edge.builder().from("display").to("END").condition("always").build())
                                .build()).graphRuntimeContext(GraphRuntimeContext.builder().runtimeType(AgentRuntimeAdapter.LANGGRAPH4J_RUNTIME_TYPE).build())
                .build());

        assertTrue(result.isSuccess());
        assertNotNull(result.getUiRequest());
        assertEquals("table", result.getUiRequest().getComponent());
        assertEquals("班组列表", result.getUiRequest().getTitle());
        assertEquals(2, ((List<Map<String, Object>>) result.getUiRequest().getData()).size());
        assertEquals("班组", ((List<Map<String, Object>>) result.getUiRequest().getSchema().get("columns")).get(0).get("label"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void pageActionNodePublishesStructuredUiRequest() {
        LangGraph4jRuntimeAdapter adapter = adapter(null);

        GraphSpec pageActionGraph = GraphSpec.builder()
                .code("page_action_graph")
                .entry("prepare")
                .finishNode("open_detail")
                .node(GraphSpec.Node.builder()
                        .id("prepare")
                        .type("VARIABLE_ASSIGN")
                        .config(Map.of(
                                "assignments", Map.of("teamId", "const:TI_DEMO01")))
                        .build())
                .node(GraphSpec.Node.builder()
                        .id("open_detail")
                        .type("PAGE_ACTION")
                        .name("Open team detail")
                        .config(Map.of(
                                "actionKey", "team.openDetail",
                                "title", "Open team detail",
                                "confirm", true,
                                "outputAlias", "page_action_result",
                                "args", Map.of("teamId", "teamId")))
                        .build())
                .edge(GraphSpec.Edge.builder().from("START").to("prepare").condition("always").build())
                .edge(GraphSpec.Edge.builder().from("prepare").to("open_detail").condition("always").build())
                .edge(GraphSpec.Edge.builder().from("open_detail").to("END").condition("always").build())
                .build();
        LangGraph4jRuntimeAdapter.WorkflowDebugRunResult result = adapter.debugRun(
                pageActionGraph,
                defaultContext(),
                "open team",
                Map.of(),
                Map.of());

        assertTrue(result.isSuccess());
        assertEquals("page_action", result.getSteps().get(1).getUiRequest().getComponent());
        assertEquals("Open team detail", result.getSteps().get(1).getUiRequest().getTitle());
        Map<String, Object> output = (Map<String, Object>) result.getSteps().get(1).getRawOutput();
        assertEquals("page.action.requested", output.get("type"));
        assertEquals("team.openDetail", output.get("actionKey"));
        assertEquals(true, output.get("confirm"));
        assertEquals("TI_DEMO01", ((Map<String, Object>) output.get("args")).get("teamId"));
        assertEquals(output, result.getFinalState().get("page_action_result"));
    }

    @Test
    void debugRunTraceSpansIncludeWorkflowMetadata() {
        AgentTraceSpanService traceSpanService = mock(AgentTraceSpanService.class);
        LangGraph4jRuntimeAdapter adapter = new LangGraph4jRuntimeAdapter(
                successModelClient(),
                mock(ToolDefinitionService.class),
                null,
                traceSpanService,
                new ObjectMapper(),
                null,
                null,
                null);

        GraphSpec graphSpec = GraphSpec.builder()
                .code("debug_graph")
                .entry("start")
                .node(GraphSpec.Node.builder()
                        .id("start")
                        .type("USER_INPUT")
                        .build())
                .node(GraphSpec.Node.builder()
                        .id("answer")
                        .type("ANSWER")
                        .config(Map.of("template", "{{ input }}"))
                        .build())
                .edge(GraphSpec.Edge.builder().from("start").to("answer").condition("always").build())
                .build();

        LangGraph4jRuntimeAdapter.WorkflowDebugRunResult result = adapter.debugRun(
                graphSpec,
                GraphRuntimeContext.builder()
                        .sourceType("WORKFLOW_DRAFT")
                        .sourceId("wf-debug")
                        .sourceKeySlug("debug-flow")
                        .runtimeType(AgentRuntimeAdapter.LANGGRAPH4J_RUNTIME_TYPE)
                        .runtimePlacement("CENTRAL")
                        .name("Debug Flow")
                        .build(),
                "hello",
                Map.of(),
                Map.of("traceId", "trace-debug"));

        assertTrue(result.isSuccess());
        verify(traceSpanService, atLeastOnce()).record(any(), any(), argThat(record ->
                "trace-debug".equals(record.traceId())
                        && record.metadata() != null
                        && "WORKFLOW_DRAFT".equals(record.metadata().get("sourceType"))
                        && "wf-debug".equals(record.metadata().get("workflowId"))
                        && "debug-flow".equals(record.metadata().get("workflowKeySlug"))));
    }

    @Test
    @SuppressWarnings("unchecked")
    void teamArchiveAssistantGraphPublishesSearchPageAction() {
        LangGraph4jRuntimeAdapter adapter = adapter(null);

        AgentRuntimeResult result = adapter.execute(AgentRuntimeRequest.builder()
                .traceId("trace-team-archive")
                .sessionId("session-team-archive")
                .message("帮我查询一下负责人为靳圣辉的班组")
                .metadata(Map.of("pageInstanceId", "page-team-archive"))
                .graphSpec(teamArchiveAssistantGraph()).graphRuntimeContext(GraphRuntimeContext.builder().runtimeType(AgentRuntimeAdapter.LANGGRAPH4J_RUNTIME_TYPE).build())
                .build());

        assertTrue(result.isSuccess());
        assertNotNull(result.getUiRequest());
        Map<String, Object> request = (Map<String, Object>) result.getUiRequest().getExtension().get("pageActionRequest");
        assertEquals("page.action.requested", request.get("type"));
        assertEquals("qmssmp.teamArchive.search", request.get("actionKey"));
        assertEquals("page-team-archive", ((Map<String, Object>) request.get("target")).get("pageInstanceId"));
        assertEquals("靳圣辉", ((Map<String, Object>) request.get("args")).get("managerName"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void pageAssistantChainWithAnswerPublishesLatestPageAction() {
        LangGraph4jRuntimeAdapter adapter = adapter(null);

        AgentRuntimeResult result = adapter.execute(AgentRuntimeRequest.builder()
                .traceId("trace-page-assistant-chain")
                .sessionId("session-page-assistant-chain")
                .message("帮我查询一下班组负责人为靳圣辉的班组信息")
                .metadata(Map.of("pageInstanceId", "page-team-archive"))
                .graphSpec(pageAssistantChainGraph())
                .graphRuntimeContext(GraphRuntimeContext.builder()
                        .runtimeType(AgentRuntimeAdapter.LANGGRAPH4J_RUNTIME_TYPE)
                        .build())
                .build());

        assertTrue(result.isSuccess());
        assertEquals("正在按你的条件查询页面数据，请稍候…", result.getAnswer());
        assertNotNull(result.getUiRequest());
        Map<String, Object> request = (Map<String, Object>) result.getUiRequest().getExtension().get("pageActionRequest");
        assertEquals("page.action.requested", request.get("type"));
        assertEquals("setFilters", request.get("actionKey"));
        assertEquals("page-team-archive", ((Map<String, Object>) request.get("target")).get("pageInstanceId"));

        List<Map<String, Object>> queue = (List<Map<String, Object>>) result.getMetadata().get("pageActionQueue");
        assertNotNull(queue);
        assertEquals(3, queue.size());
        assertEquals("setFilters", queue.get(0).get("actionKey"));
        assertEquals("search", queue.get(1).get("actionKey"));
        assertEquals("readTable", queue.get(2).get("actionKey"));
        assertEquals("靳圣辉", ((Map<String, Object>) queue.get(0).get("args")).get("managerName"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void parameterExtractSetFiltersPublishesManagerNameWithMustacheArgs() {
        LangGraph4jRuntimeAdapter adapter = new LangGraph4jRuntimeAdapter(
                jsonModelClient("{\"teamName\":null,\"memberName\":null,\"organCode\":null,\"managerName\":null}"),
                mock(ToolDefinitionService.class),
                null,
                mock(AgentTraceSpanService.class),
                new ObjectMapper(),
                null,
                null,
                null);

        AgentRuntimeResult result = adapter.execute(AgentRuntimeRequest.builder()
                .traceId("trace-parameter-extract-set-filters")
                .sessionId("session-parameter-extract-set-filters")
                .message("帮我查询一下负责人为靳圣辉的班组")
                .metadata(Map.of("pageInstanceId", "page-team-archive"))
                .graphSpec(parameterExtractSetFiltersGraph())
                .graphRuntimeContext(GraphRuntimeContext.builder()
                        .runtimeType(AgentRuntimeAdapter.LANGGRAPH4J_RUNTIME_TYPE)
                        .modelInstanceId("llm-1")
                        .build())
                .build());

        assertTrue(result.isSuccess());
        List<Map<String, Object>> queue = (List<Map<String, Object>>) result.getMetadata().get("pageActionQueue");
        assertNotNull(queue);
        assertEquals(3, queue.size());
        assertEquals("setFilters", queue.get(0).get("actionKey"));
        assertEquals("search", queue.get(1).get("actionKey"));
        assertEquals("readTable", queue.get(2).get("actionKey"));
        assertEquals("靳圣辉", ((Map<String, Object>) queue.get(0).get("args")).get("managerName"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void parameterExtractSetFiltersPublishesMemberAndTeamName() {
        LangGraph4jRuntimeAdapter adapter = new LangGraph4jRuntimeAdapter(
                jsonModelClient("{}"),
                mock(ToolDefinitionService.class),
                null,
                mock(AgentTraceSpanService.class),
                new ObjectMapper(),
                null,
                null,
                null);

        AgentRuntimeResult memberResult = adapter.execute(AgentRuntimeRequest.builder()
                .traceId("trace-parameter-extract-member")
                .sessionId("session-parameter-extract-member")
                .message("查一下成员为张三的班组")
                .metadata(Map.of("pageInstanceId", "page-team-archive"))
                .graphSpec(parameterExtractSetFiltersGraph())
                .graphRuntimeContext(GraphRuntimeContext.builder()
                        .runtimeType(AgentRuntimeAdapter.LANGGRAPH4J_RUNTIME_TYPE)
                        .modelInstanceId("llm-1")
                        .build())
                .build());
        assertTrue(memberResult.isSuccess());
        List<Map<String, Object>> memberQueue = (List<Map<String, Object>>) memberResult.getMetadata().get("pageActionQueue");
        assertEquals("张三", ((Map<String, Object>) memberQueue.get(0).get("args")).get("memberName"));

        AgentRuntimeResult teamResult = adapter.execute(AgentRuntimeRequest.builder()
                .traceId("trace-parameter-extract-team")
                .sessionId("session-parameter-extract-team")
                .message("查一下班组名称包含检修的班组")
                .metadata(Map.of("pageInstanceId", "page-team-archive"))
                .graphSpec(parameterExtractSetFiltersGraph())
                .graphRuntimeContext(GraphRuntimeContext.builder()
                        .runtimeType(AgentRuntimeAdapter.LANGGRAPH4J_RUNTIME_TYPE)
                        .modelInstanceId("llm-1")
                        .build())
                .build());
        assertTrue(teamResult.isSuccess());
        List<Map<String, Object>> teamQueue = (List<Map<String, Object>>) teamResult.getMetadata().get("pageActionQueue");
        assertEquals("检修", ((Map<String, Object>) teamQueue.get(0).get("args")).get("teamName"));
    }

    @Test
    void interactionCollectInputPublishesExplicitTargetArgs() {
        LangGraph4jRuntimeAdapter adapter = adapter(null);

        AgentRuntimeResult result = adapter.execute(AgentRuntimeRequest.builder()
                .traceId("trace-interaction-slot")
                .sessionId("session-interaction-slot")
                .message("查询一下研发一组的班组信息")
                .runtimeOptions(Map.of("params", Map.of("teamName", "研发一组")))
                .graphSpec(GraphSpec.builder()
                                .code("interaction_slot_graph")
                                .entry("collect")
                                .finishNode("answer")
                                .node(GraphSpec.Node.builder()
                                        .id("collect")
                                        .type("INTERACTION")
                                        .config(Map.of(
                                                "interactionType", "COLLECT_INPUT",
                                                "outputAlias", "interaction_output",
                                                "fields", List.of(Map.of(
                                                        "key", "teamName",
                                                        "label", "班组名称",
                                                        "targetPath", "body_json.teamName",
                                                        "type", "string",
                                                        "required", true))))
                                        .build())
                                .node(GraphSpec.Node.builder()
                                        .id("answer")
                                        .type("ANSWER")
                                        .config(Map.of("template", "{{ interaction_output.targetArgs.body_json.teamName }}"))
                                        .build())
                                .edge(GraphSpec.Edge.builder().from("START").to("collect").condition("always").build())
                                .edge(GraphSpec.Edge.builder().from("collect").to("answer").condition("always").build())
                                .edge(GraphSpec.Edge.builder().from("answer").to("END").condition("always").build())
                                .build()).graphRuntimeContext(GraphRuntimeContext.builder().runtimeType(AgentRuntimeAdapter.LANGGRAPH4J_RUNTIME_TYPE).build())
                .build());

        assertTrue(result.isSuccess());
        assertEquals("研发一组", result.getAnswer());
    }

    @Test
    void interactionCollectInputDoesNotGuessRequiredFieldByDefault() {
        LangGraph4jRuntimeAdapter adapter = adapter(null);
        GraphSpec graphSpec = GraphSpec.builder()
                .code("interaction_no_guess_graph")
                .entry("collect")
                .finishNode("answer")
                .node(GraphSpec.Node.builder()
                        .id("collect")
                        .type("INTERACTION")
                        .config(Map.of(
                                "interactionType", "COLLECT_INPUT",
                                "outputAlias", "interaction_output",
                                "fields", List.of(Map.of(
                                        "key", "teamName",
                                        "label", "班组名称",
                                        "targetPath", "body_json.teamName",
                                        "type", "string",
                                        "required", true))))
                        .build())
                .node(GraphSpec.Node.builder()
                        .id("answer")
                        .type("ANSWER")
                        .config(Map.of("template", "{{ interaction_output.targetArgs.body_json.teamName }}"))
                        .build())
                .edge(GraphSpec.Edge.builder().from("START").to("collect").condition("always").build())
                .edge(GraphSpec.Edge.builder().from("collect").to("answer").condition("always").build())
                .edge(GraphSpec.Edge.builder().from("answer").to("END").condition("always").build())
                .build();

        LangGraph4jRuntimeAdapter.WorkflowDebugRunResult result = adapter.debugRun(
                graphSpec,
                defaultContext(),
                "查询一下研发一组的班组信息",
                Map.of(),
                Map.of());

        assertTrue(result.isSuccess());
        assertEquals("WAITING", result.getStatus());
        assertTrue(result.getAnswer().contains("班组名称"));
        assertEquals("WAITING", result.getSteps().get(0).getStatus());
    }

    @Test
    void interactionSlotFillingLlmPublishesTargetArgsWhenConfigured() {
        LangGraph4jRuntimeAdapter adapter = new LangGraph4jRuntimeAdapter(
                jsonModelClient("{\"values\":{\"teamName\":\"研发一组\"},\"confidence\":{\"teamName\":0.93}}"),
                mock(ToolDefinitionService.class),
                null,
                mock(AgentTraceSpanService.class),
                new ObjectMapper(),
                null,
                null,
                null);

        AgentRuntimeResult result = adapter.execute(AgentRuntimeRequest.builder()
                .traceId("trace-interaction-llm-slot")
                .sessionId("session-interaction-llm-slot")
                .message("查询一下研发一组的班组信息")
                .runtimeOptions(Map.of("params", Map.of()))
                .graphSpec(GraphSpec.builder()
                                .code("interaction_llm_slot_graph")
                                .entry("collect")
                                .finishNode("answer")
                                .node(GraphSpec.Node.builder()
                                        .id("collect")
                                        .type("INTERACTION")
                                        .config(Map.of(
                                                "interactionType", "COLLECT_INPUT",
                                                "outputAlias", "interaction_output",
                                                "fields", List.of(Map.of(
                                                        "key", "teamName",
                                                        "label", "班组名称",
                                                        "targetPath", "body_json.teamName",
                                                        "type", "string",
                                                        "required", true,
                                                        "slotFilling", Map.of(
                                                                "enabled", true,
                                                                "strategies", List.of("LLM"),
                                                                "confirmPolicy", "NEVER",
                                                                "confidenceThreshold", 0.85)))))
                                        .build())
                                .node(GraphSpec.Node.builder()
                                        .id("answer")
                                        .type("ANSWER")
                                        .config(Map.of("template", "{{ interaction_output.targetArgs.body_json.teamName }}"))
                                        .build())
                                .edge(GraphSpec.Edge.builder().from("START").to("collect").condition("always").build())
                                .edge(GraphSpec.Edge.builder().from("collect").to("answer").condition("always").build())
                                .edge(GraphSpec.Edge.builder().from("answer").to("END").condition("always").build())
                                .build()).graphRuntimeContext(GraphRuntimeContext.builder().runtimeType(AgentRuntimeAdapter.LANGGRAPH4J_RUNTIME_TYPE).modelInstanceId("llm-1").build())
                .build());

        assertTrue(result.isSuccess());
        assertEquals("研发一组", result.getAnswer());
    }

    @Test
    void interactionSlotFillingEnabledUserInputOnlyFallsBackToLlmExtraction() {
        LangGraph4jRuntimeAdapter adapter = new LangGraph4jRuntimeAdapter(
                jsonModelClient("{\"values\":{\"teamName\":\"研发一组\"},\"confidence\":{\"teamName\":0.93}}"),
                mock(ToolDefinitionService.class),
                null,
                mock(AgentTraceSpanService.class),
                new ObjectMapper(),
                null,
                null,
                null);

        AgentRuntimeResult result = adapter.execute(AgentRuntimeRequest.builder()
                .traceId("trace-interaction-enabled-user-input-slot")
                .sessionId("session-interaction-enabled-user-input-slot")
                .message("帮我查询一下研发一组的班组信息")
                .runtimeOptions(Map.of("params", Map.of()))
                .graphSpec(GraphSpec.builder()
                                .code("interaction_enabled_user_input_slot_graph")
                                .entry("collect")
                                .finishNode("answer")
                                .node(GraphSpec.Node.builder()
                                        .id("collect")
                                        .type("INTERACTION")
                                        .config(Map.of(
                                                "interactionType", "COLLECT_INPUT",
                                                "outputAlias", "interaction_output",
                                                "fields", List.of(Map.of(
                                                        "key", "teamName",
                                                        "label", "班组名称",
                                                        "targetPath", "body_json.teamName",
                                                        "type", "string",
                                                        "required", true,
                                                        "slotFilling", Map.of(
                                                                "enabled", true,
                                                                "strategies", List.of("USER_INPUT"),
                                                                "confirmPolicy", "NEVER",
                                                                "confidenceThreshold", 0.85)))))
                                        .build())
                                .node(GraphSpec.Node.builder()
                                        .id("answer")
                                        .type("ANSWER")
                                        .config(Map.of("template", "{{ interaction_output.targetArgs.body_json.teamName }}"))
                                        .build())
                                .edge(GraphSpec.Edge.builder().from("START").to("collect").condition("always").build())
                                .edge(GraphSpec.Edge.builder().from("collect").to("answer").condition("always").build())
                                .edge(GraphSpec.Edge.builder().from("answer").to("END").condition("always").build())
                                .build()).graphRuntimeContext(GraphRuntimeContext.builder().runtimeType(AgentRuntimeAdapter.LANGGRAPH4J_RUNTIME_TYPE).modelInstanceId("llm-1").build())
                .build());

        assertTrue(result.isSuccess());
        assertEquals("研发一组", result.getAnswer());
    }

    @Test
    void interactionSlotFillingWaitsForConfirmationWhenConfidenceIsLow() {
        LangGraph4jRuntimeAdapter adapter = new LangGraph4jRuntimeAdapter(
                jsonModelClient("{\"values\":{\"teamName\":\"研发一组\"},\"confidence\":{\"teamName\":0.42}}"),
                mock(ToolDefinitionService.class),
                null,
                mock(AgentTraceSpanService.class),
                new ObjectMapper(),
                null,
                null,
                null);

        GraphSpec graphSpec = GraphSpec.builder()
                .code("interaction_low_confidence_graph")
                .entry("collect")
                .finishNode("answer")
                .node(GraphSpec.Node.builder()
                        .id("collect")
                        .type("INTERACTION")
                        .config(Map.of(
                                "interactionType", "COLLECT_INPUT",
                                "outputAlias", "interaction_output",
                                "fields", List.of(Map.of(
                                        "key", "teamName",
                                        "label", "班组名称",
                                        "targetPath", "body_json.teamName",
                                        "type", "string",
                                        "required", true,
                                        "slotFilling", Map.of(
                                                "enabled", true,
                                                "strategies", List.of("LLM"),
                                                "confirmPolicy", "LOW_CONFIDENCE",
                                                "confidenceThreshold", 0.85)))))
                        .build())
                .node(GraphSpec.Node.builder()
                        .id("answer")
                        .type("ANSWER")
                        .config(Map.of("template", "{{ interaction_output.targetArgs.body_json.teamName }}"))
                        .build())
                .edge(GraphSpec.Edge.builder().from("START").to("collect").condition("always").build())
                .edge(GraphSpec.Edge.builder().from("collect").to("answer").condition("always").build())
                .edge(GraphSpec.Edge.builder().from("answer").to("END").condition("always").build())
                .build();

        LangGraph4jRuntimeAdapter.WorkflowDebugRunResult result = adapter.debugRun(
                graphSpec,
                GraphRuntimeContext.builder()
                        .runtimeType(AgentRuntimeAdapter.LANGGRAPH4J_RUNTIME_TYPE)
                        .modelInstanceId("llm-1")
                        .build(),
                "查询一下研发一组的班组信息",
                Map.of(),
                Map.of());

        assertTrue(result.isSuccess());
        assertEquals("WAITING", result.getStatus());
        assertTrue(result.getAnswer().contains("请确认"));
        assertTrue(result.getSteps().get(0).getRawOutput().toString().contains("研发一组"));
    }

    @Test
    void interactionCollectInputWaitsForMissingRequiredFieldsInDebugRun() {
        LangGraph4jRuntimeAdapter adapter = adapter(null);
        GraphSpec graphSpec = GraphSpec.builder()
                .code("interaction_missing_fields_graph")
                .entry("collect")
                .finishNode("answer")
                .node(GraphSpec.Node.builder()
                        .id("collect")
                        .type("INTERACTION")
                        .config(Map.of(
                                "interactionType", "COLLECT_INPUT",
                                "outputAlias", "interaction_output",
                                "fields", List.of(Map.of(
                                        "key", "teamName",
                                        "label", "班组名称",
                                        "targetPath", "body_json.teamName",
                                        "type", "string",
                                        "required", true))))
                        .build())
                .node(GraphSpec.Node.builder()
                        .id("answer")
                        .type("ANSWER")
                        .config(Map.of("template", "{{ interaction_output.targetArgs.body_json.teamName }}"))
                        .build())
                .edge(GraphSpec.Edge.builder().from("START").to("collect").condition("always").build())
                .edge(GraphSpec.Edge.builder().from("collect").to("answer").condition("always").build())
                .edge(GraphSpec.Edge.builder().from("answer").to("END").condition("always").build())
                .build();

        LangGraph4jRuntimeAdapter.WorkflowDebugRunResult result = adapter.debugRun(
                graphSpec,
                defaultContext(),
                "查询班组信息",
                Map.of(),
                Map.of());

        assertTrue(result.isSuccess());
        assertEquals("WAITING", result.getStatus());
        assertTrue(result.getAnswer().contains("请补充"));
        assertEquals("WAITING", result.getSteps().get(0).getStatus());
        assertNotNull(result.getSteps().get(0).getRawOutput());
    }

    @Test
    @SuppressWarnings("unchecked")
    void debugRunExposesResolvedInputsRawOutputsAndPublishedVariables() {
        LangGraph4jRuntimeAdapter adapter = adapter(null);
        GraphSpec graphSpec = GraphSpec.builder()
                .code("variable_contract_graph")
                .entry("user_input")
                .finishNode("reply")
                .node(GraphSpec.Node.builder()
                        .id("user_input")
                        .type("USER_INPUT")
                        .config(Map.of(
                                "outputAlias", "params",
                                "fields", List.of(Map.of(
                                        "name", "question",
                                        "type", "string",
                                        "required", true))))
                        .build())
                .node(GraphSpec.Node.builder()
                        .id("assign")
                        .type("VARIABLE_ASSIGN")
                        .inputs(List.of(GraphSpec.Port.builder()
                                .id("question")
                                .name("question")
                                .type("string")
                                .required(true)
                                .build()))
                        .config(Map.of(
                                "inputMapping", Map.of("question", "params.question"),
                                "assignments", Map.of("question", "params.question"),
                                "outputAlias", "captured"))
                        .build())
                .node(GraphSpec.Node.builder()
                        .id("reply")
                        .type("ANSWER")
                        .config(Map.of("template", "{{ captured.question }}"))
                        .build())
                .edge(GraphSpec.Edge.builder().from("START").to("user_input").condition("always").build())
                .edge(GraphSpec.Edge.builder().from("user_input").to("assign").condition("always").build())
                .edge(GraphSpec.Edge.builder().from("assign").to("reply").condition("always").build())
                .edge(GraphSpec.Edge.builder().from("reply").to("END").condition("always").build())
                .build();

        LangGraph4jRuntimeAdapter.WorkflowDebugRunResult result = adapter.debugRun(
                graphSpec,
                defaultContext(),
                "hello",
                Map.of("question", "hello"),
                Map.of());

        assertTrue(result.isSuccess());
        LangGraph4jRuntimeAdapter.WorkflowDebugStepResult assignStep = result.getSteps().stream()
                .filter(step -> "assign".equals(step.getNodeId()))
                .findFirst()
                .orElseThrow();
        assertEquals(Map.of("question", "hello"), assignStep.getResolvedInput());
        assertEquals(Map.of("question", "hello"), assignStep.getRawOutput());
        Map<String, Object> published = assignStep.getPublishedVariables();
        assertEquals(Map.of("question", "hello"), published.get("captured"));
        assertEquals(Map.of("question", "hello"), published.get("var.captured"));
        assertEquals(Map.of("question", "hello"), ((Map<String, Object>) result.getFinalState().get("captured")));
    }

    @Test
    @SuppressWarnings("unchecked")
    void toolInputMappingUsesNullForMissingPathReferencesInsteadOfExpressionText() {
        ToolDefinitionService toolDefinitionService = mock(ToolDefinitionService.class);
        when(toolDefinitionService.findByName(any())).thenReturn(Optional.empty());
        when(toolDefinitionService.findByQualifiedName(any())).thenReturn(Optional.empty());
        when(toolDefinitionService.executeTool(eq("team_page"), any(), any())).thenReturn(Map.of("ok", true));
        LangGraph4jRuntimeAdapter adapter = new LangGraph4jRuntimeAdapter(
                successModelClient(),
                toolDefinitionService,
                null,
                mock(AgentTraceSpanService.class),
                new ObjectMapper(),
                null,
                null,
                null);

        AgentRuntimeResult result = adapter.execute(AgentRuntimeRequest.builder()
                .traceId("trace-tool-mapping")
                .sessionId("session-tool-mapping")
                .message("query team")
                .graphSpec(GraphSpec.builder()
                                .code("tool_mapping_graph")
                                .entry("collect")
                                .finishNode("tool")
                                .node(GraphSpec.Node.builder()
                                        .id("collect")
                                        .type("INTERACTION")
                                        .config(Map.of(
                                                "interactionType", "COLLECT_INPUT",
                                                "outputAlias", "interaction_output",
                                                "fields", List.of(
                                                        Map.of("key", "body_json_teamName", "targetPath", "body_json.teamName", "type", "string", "required", false),
                                                        Map.of("key", "body_json_deptId", "targetPath", "body_json.deptId", "type", "string", "required", false))))
                                        .build())
                                .node(GraphSpec.Node.builder()
                                        .id("tool")
                                        .type("TOOL")
                                        .config(Map.of(
                                                "qualifiedName", "team_page",
                                                "inputMapping", Map.of(
                                                        "body_json.teamName", "interaction_output.body_json_teamName",
                                                        "body_json.deptId", "interaction_output.body_json_deptId",
                                                        "body_json.source", "const:agent-studio")))
                                        .build())
                                .edge(GraphSpec.Edge.builder().from("START").to("collect").condition("always").build())
                                .edge(GraphSpec.Edge.builder().from("collect").to("tool").condition("always").build())
                                .edge(GraphSpec.Edge.builder().from("tool").to("END").condition("always").build())
                                .build()).graphRuntimeContext(GraphRuntimeContext.builder().runtimeType(AgentRuntimeAdapter.LANGGRAPH4J_RUNTIME_TYPE).build())
                .runtimeOptions(Map.of("params", Map.of("body_json_teamName", "研发一组")))
                .build());

        assertTrue(result.isSuccess());
        verify(toolDefinitionService).executeTool(eq("team_page"), argThat(args -> {
            Map<String, Object> body = (Map<String, Object>) args.get("body_json");
            return body != null
                    && "研发一组".equals(body.get("teamName"))
                    && body.containsKey("deptId")
                    && body.get("deptId") == null
                    && "agent-studio".equals(body.get("source"));
        }), any());
    }

    @Test
    void toolNodePassesConfiguredRequestTimeoutToDynamicToolExecution() {
        ToolDefinitionService toolDefinitionService = mock(ToolDefinitionService.class);
        when(toolDefinitionService.findByName(any())).thenReturn(Optional.empty());
        when(toolDefinitionService.findByQualifiedName(any())).thenReturn(Optional.empty());
        when(toolDefinitionService.executeTool(eq("slow_api"), any(), eq(Duration.ofMillis(180_000))))
                .thenReturn(Map.of("ok", true));
        LangGraph4jRuntimeAdapter adapter = new LangGraph4jRuntimeAdapter(
                successModelClient(),
                toolDefinitionService,
                null,
                mock(AgentTraceSpanService.class),
                new ObjectMapper(),
                null,
                null,
                null);

        AgentRuntimeResult result = adapter.execute(AgentRuntimeRequest.builder()
                .traceId("trace-tool-timeout")
                .sessionId("session-tool-timeout")
                .message("query slow api")
                .graphSpec(GraphSpec.builder()
                                .code("tool_timeout_graph")
                                .entry("tool")
                                .finishNode("tool")
                                .node(GraphSpec.Node.builder()
                                        .id("tool")
                                        .type("TOOL")
                                        .config(Map.of(
                                                "qualifiedName", "slow_api",
                                                "maxRequestTimeMs", 180_000,
                                                "inputMapping", Map.of(
                                                        "body_json.keyword", "const:test")))
                                        .build())
                                .edge(GraphSpec.Edge.builder().from("START").to("tool").condition("always").build())
                                .edge(GraphSpec.Edge.builder().from("tool").to("END").condition("always").build())
                                .build()).graphRuntimeContext(GraphRuntimeContext.builder().runtimeType(AgentRuntimeAdapter.LANGGRAPH4J_RUNTIME_TYPE).build())
                .build());

        assertTrue(result.isSuccess());
        verify(toolDefinitionService).executeTool(eq("slow_api"), any(), eq(Duration.ofMillis(180_000)));
    }

    @Test
    void debugRunReturnsWorkflowStepsAndBranchRoute() {
        LangGraph4jRuntimeAdapter adapter = adapter(null);
        GraphSpec graphSpec = GraphSpec.builder()
                .code("debug_graph")
                .entry("user_input")
                .finishNode("metro_answer")
                .finishNode("unknown_reply")
                .node(GraphSpec.Node.builder()
                        .id("user_input")
                        .type("USER_INPUT")
                        .name("用户输入")
                        .config(Map.of(
                                "outputAlias", "params",
                                "fields", List.of(Map.of(
                                        "name", "question",
                                        "type", "string",
                                        "required", true))))
                        .build())
                .node(GraphSpec.Node.builder()
                        .id("intent_classifier")
                        .type("INTENT_CLASSIFIER")
                        .name("意图识别")
                        .config(Map.of(
                                "inputExpression", "params.question",
                                "classes", List.of(Map.of(
                                        "id", "metro_question",
                                        "label", "地铁相关",
                                        "keywords", List.of("地铁"))),
                                "defaultRoute", "irrelevant"))
                        .build())
                .node(GraphSpec.Node.builder()
                        .id("metro_answer")
                        .type("ANSWER")
                        .name("地铁问题回答")
                        .config(Map.of("template", "metro: {{ params.question }}"))
                        .build())
                .node(GraphSpec.Node.builder()
                        .id("unknown_reply")
                        .type("ANSWER")
                        .name("未知回复")
                        .config(Map.of("template", "我不知道"))
                        .build())
                .edge(GraphSpec.Edge.builder().from("START").to("user_input").condition("always").build())
                .edge(GraphSpec.Edge.builder().from("user_input").to("intent_classifier").condition("always").build())
                .edge(GraphSpec.Edge.builder().from("intent_classifier").to("metro_answer").condition("route:metro_question").build())
                .edge(GraphSpec.Edge.builder().from("intent_classifier").to("unknown_reply").condition("route:irrelevant").build())
                .edge(GraphSpec.Edge.builder().from("metro_answer").to("END").condition("always").build())
                .edge(GraphSpec.Edge.builder().from("unknown_reply").to("END").condition("always").build())
                .build();

        LangGraph4jRuntimeAdapter.WorkflowDebugRunResult result = adapter.debugRun(
                graphSpec,
                defaultContext(),
                "今天天气怎么样",
                Map.of("question", "今天天气怎么样"),
                Map.of());

        assertFalse(result.getRunId().isBlank());
        assertFalse(result.getTraceId().isBlank());
        assertEquals("SUCCESS", result.getStatus());
        assertEquals("我不知道", result.getAnswer());
        assertEquals(3, result.getSteps().size());
        assertEquals("user_input", result.getSteps().get(0).getNodeId());
        assertEquals("intent_classifier", result.getSteps().get(1).getNodeId());
        assertEquals("irrelevant", result.getSteps().get(1).getRoute());
        assertEquals("unknown_reply", result.getSteps().get(1).getNextNodeId());
        assertEquals("unknown_reply", result.getSteps().get(2).getNodeId());
        assertEquals("我不知道", result.getFinalState().get("answer"));
    }

    @Test
    void executesSingleAgentThroughLangGraph4j() {
        ToolCallLogService logService = mock(ToolCallLogService.class);
        LangGraph4jRuntimeAdapter adapter = adapter(logService);

        AgentRuntimeResult result = adapter.execute(AgentRuntimeRequest.builder()
                .traceId("trace-lg")
                .sessionId("session-lg")
                .userId("user-lg")
                .message("hello")
                .intentType("GENERAL_CHAT")
                .graphSpec(singleLlmGraph("llm")).graphRuntimeContext(GraphRuntimeContext.builder().name("LangGraph Agent").runtimeType(AgentRuntimeAdapter.LANGGRAPH4J_RUNTIME_TYPE).modelInstanceId("llm-1").systemPrompt("You are helpful.").build())
                .build());

        assertTrue(result.isSuccess());
        assertEquals("langgraph answer", result.getAnswer());
        assertEquals(AgentRuntimeAdapter.LANGGRAPH4J_RUNTIME_TYPE, result.getRuntimeType());
        assertEquals("qwen-plus", result.getMetadata().get("model"));
        assertEquals("tongyi", result.getMetadata().get("provider"));
        assertEquals(2, result.getSteps().size());
        assertEquals(7, result.getTokenUsage().get("totalTokens"));

        verify(logService).record(any(), eq("runtime.langgraph4j.node.llm"),
                any(), any(), eq(true), eq(null), any(Long.class), eq(7));
        verify(logService).record(any(), eq("runtime.agent.run"),
                any(), any(), eq(true), eq(null), any(Long.class), eq(null));
    }

    @Test
    @SuppressWarnings("unchecked")
    void executesConfiguredLlmNodeFromGraphSpec() {
        ToolCallLogService logService = mock(ToolCallLogService.class);
        LangGraph4jRuntimeAdapter adapter = adapter(logService);

        AgentRuntimeResult result = adapter.execute(AgentRuntimeRequest.builder()
                .traceId("trace-lg")
                .sessionId("session-lg")
                .message("hello")
                .graphSpec(singleLlmGraph("planner")).graphRuntimeContext(GraphRuntimeContext.builder().runtimeType(AgentRuntimeAdapter.LANGGRAPH4J_RUNTIME_TYPE).modelInstanceId("llm-1").systemPrompt("You are helpful.").build())
                .build());

        assertTrue(result.isSuccess());
        assertTrue(((List<String>) result.getMetadata().get("graphNodes")).contains("planner"));
        assertTrue(result.getSteps().contains("Graph node: planner (LLM)"));
        verify(logService).record(any(), eq("runtime.langgraph4j.node.planner"),
                any(), any(), eq(true), eq(null), any(Long.class), eq(7));
    }

    @Test
    @SuppressWarnings("unchecked")
    void parsesJsonLlmOutputWithSchemaDefaults() {
        LangGraph4jRuntimeAdapter adapter = new LangGraph4jRuntimeAdapter(
                jsonModelClient("{\"orderId\":\"1001\",\"amount\":\"42\"}"),
                mock(ToolDefinitionService.class),
                null,
                mock(AgentTraceSpanService.class),
                new ObjectMapper(),
                null,
                null,
                null);

        AgentRuntimeResult result = adapter.execute(AgentRuntimeRequest.builder()
                .traceId("trace-json")
                .sessionId("session-json")
                .message("order 1001")
                .graphSpec(GraphSpec.builder()
                                .code("json_graph")
                                .entry("llm")
                                .finishNode("reply")
                                .node(GraphSpec.Node.builder()
                                        .id("llm")
                                        .type("LLM")
                                        .config(Map.of(
                                                "modelInstanceId", "llm-1",
                                                "outputAlias", "parsed",
                                                "outputFormat", "json",
                                                "outputSchema", List.of(
                                                        Map.of("name", "orderId", "type", "string", "required", true),
                                                        Map.of("name", "amount", "type", "integer", "required", true),
                                                        Map.of("name", "status", "type", "string", "defaultValue", "NEW"))))
                                        .build())
                                .node(GraphSpec.Node.builder()
                                        .id("reply")
                                        .type("TEMPLATE")
                                        .config(Map.of("template", "Order {{ parsed.orderId }} amount {{ parsed.amount }} status {{ parsed.status }}"))
                                        .build())
                                .edge(GraphSpec.Edge.builder().from("START").to("llm").condition("always").build())
                                .edge(GraphSpec.Edge.builder().from("llm").to("reply").condition("always").build())
                                .edge(GraphSpec.Edge.builder().from("reply").to("END").condition("always").build())
                                .build()).graphRuntimeContext(GraphRuntimeContext.builder().runtimeType(AgentRuntimeAdapter.LANGGRAPH4J_RUNTIME_TYPE).modelInstanceId("llm-1").systemPrompt("You are helpful.").build())
                .build());

        assertTrue(result.isSuccess());
        assertEquals("Order 1001 amount 42 status NEW", result.getAnswer());
    }

    @Test
    void llmNodeUsesConfiguredMessageArrayAndStructuredInstruction() {
        ModelServiceClient modelClient = mock(ModelServiceClient.class);
        when(modelClient.chat(any())).thenReturn(new ModelServiceClient.ModelChatResult(
                200,
                "ok",
                new ModelServiceClient.ModelChatData(
                        "{\"summary\":\"done\"}",
                        "qwen-plus",
                        "tongyi",
                        new ModelServiceClient.ModelUsage(3, 4, 7),
                        null,
                        null,
                        "stop")));
        LangGraph4jRuntimeAdapter adapter = new LangGraph4jRuntimeAdapter(
                modelClient,
                mock(ToolDefinitionService.class),
                null,
                mock(AgentTraceSpanService.class),
                new ObjectMapper(),
                null,
                null,
                null);

        AgentRuntimeResult result = adapter.execute(AgentRuntimeRequest.builder()
                .traceId("trace-messages")
                .sessionId("session-messages")
                .message("hello")
                .graphSpec(GraphSpec.builder()
                                .code("messages_graph")
                                .entry("llm")
                                .finishNode("llm")
                                .node(GraphSpec.Node.builder()
                                        .id("llm")
                                        .type("LLM")
                                        .config(Map.of(
                                                "modelInstanceId", "llm-1",
                                                "messages", List.of(
                                                        Map.of("role", "system", "content", "You summarize."),
                                                        Map.of("role", "user", "content", "Input: {{ input }}")),
                                                "structuredOutput", true,
                                                "outputSchema", List.of(Map.of("name", "summary", "type", "string", "required", true))))
                                        .build())
                                .edge(GraphSpec.Edge.builder().from("START").to("llm").condition("always").build())
                                .edge(GraphSpec.Edge.builder().from("llm").to("END").condition("always").build())
                                .build()).graphRuntimeContext(GraphRuntimeContext.builder().runtimeType(AgentRuntimeAdapter.LANGGRAPH4J_RUNTIME_TYPE).modelInstanceId("llm-1").build())
                .build());

        assertTrue(result.isSuccess());
        assertEquals("{summary=done}", result.getAnswer());
        verify(modelClient).chat(argThat(request ->
                request.getMessages().size() == 3
                        && "system".equals(request.getMessages().get(0).getRole())
                        && "Input: hello".equals(request.getMessages().get(1).getContent())
                        && request.getMessages().get(2).getContent().contains("Return only a valid JSON object")));
    }

    @Test
    void llmNodeInjectsSelectedContextVariablesThatAreNotAlreadyReferencedInMessages() {
        ModelServiceClient modelClient = mock(ModelServiceClient.class);
        when(modelClient.chat(any())).thenReturn(new ModelServiceClient.ModelChatResult(
                200,
                "ok",
                new ModelServiceClient.ModelChatData(
                        "team-a found",
                        "qwen-plus",
                        "tongyi",
                        new ModelServiceClient.ModelUsage(20, 5, 25),
                        null,
                        null,
                        "stop")));
        LangGraph4jRuntimeAdapter adapter = new LangGraph4jRuntimeAdapter(
                modelClient,
                mock(ToolDefinitionService.class),
                null,
                mock(AgentTraceSpanService.class),
                new ObjectMapper(),
                null,
                null,
                null);

        AgentRuntimeResult result = adapter.execute(AgentRuntimeRequest.builder()
                .traceId("trace-llm-context-vars")
                .sessionId("session-llm-context-vars")
                .message("help me query team-a")
                .graphSpec(GraphSpec.builder()
                                .code("llm_context_vars_graph")
                                .entry("tool_result")
                                .finishNode("llm")
                                .node(GraphSpec.Node.builder()
                                        .id("tool_result")
                                        .type("TEMPLATE")
                                        .config(Map.of("template", "{\"records\":[{\"teamName\":\"team-a\"}]}"))
                                        .build())
                                .node(GraphSpec.Node.builder()
                                        .id("llm")
                                        .type("LLM")
                                        .config(Map.of(
                                                "modelInstanceId", "llm-1",
                                                "messages", List.of(
                                                        Map.of("role", "system", "content", "Summarize tool result."),
                                                        Map.of("role", "user", "content", "Question: {{ input }}")),
                                                "contextVariables", List.of("input", "lastOutput")))
                                        .build())
                                .edge(GraphSpec.Edge.builder().from("START").to("tool_result").condition("always").build())
                                .edge(GraphSpec.Edge.builder().from("tool_result").to("llm").condition("always").build())
                                .edge(GraphSpec.Edge.builder().from("llm").to("END").condition("always").build())
                                .build()).graphRuntimeContext(GraphRuntimeContext.builder().runtimeType(AgentRuntimeAdapter.LANGGRAPH4J_RUNTIME_TYPE).modelInstanceId("llm-1").build())
                .build());

        assertTrue(result.isSuccess());
        verify(modelClient).chat(argThat(request ->
                request.getMessages().size() == 3
                        && "Question: help me query team-a".equals(request.getMessages().get(1).getContent())
                        && !request.getMessages().get(2).getContent().contains("input =")
                        && request.getMessages().get(2).getContent().contains("lastOutput = {\"records\"")));
    }

    @Test
    void debugsSingleTemplateNodeWithStateOverrides() {
        LangGraph4jRuntimeAdapter adapter = adapter(null);

        GraphSpec debugGraph = GraphSpec.builder()
                .code("debug_graph")
                .entry("reply")
                .finishNode("reply")
                .node(GraphSpec.Node.builder()
                        .id("reply")
                        .type("TEMPLATE")
                        .config(Map.of("template", "Hello {{ customer.name }}", "writeToAnswer", true))
                        .build())
                .build();
        LangGraph4jRuntimeAdapter.NodeDebugResult result = adapter.debugNode(
                debugGraph,
                GraphRuntimeContext.builder()
                        .runtimeType(AgentRuntimeAdapter.LANGGRAPH4J_RUNTIME_TYPE)
                        .modelInstanceId("llm-1")
                        .systemPrompt("You are helpful.")
                        .build(),
                "reply",
                "hello",
                Map.of("customer", Map.of("name", "Ada")));

        assertTrue(result.isSuccess());
        assertEquals("Hello Ada", result.getOutputState().get("answer"));
        assertEquals("Hello Ada", result.getNodeOutput());
    }

    @Test
    void graphNativeDebugNodeExecutesTemplateNode() {
        LangGraph4jRuntimeAdapter adapter = adapter(null);
        GraphSpec graphSpec = GraphSpec.builder()
                .code("debug_graph")
                .entry("reply")
                .finishNode("reply")
                .node(GraphSpec.Node.builder()
                        .id("reply")
                        .type("TEMPLATE")
                        .config(Map.of("template", "Hello {{ customer.name }}", "writeToAnswer", true))
                        .build())
                .build();
        Map<String, Object> stateOverrides = Map.of("customer", Map.of("name", "Ada"));

        LangGraph4jRuntimeAdapter.NodeDebugResult graphNative = adapter.debugNode(
                graphSpec,
                defaultContext(),
                "reply",
                "hello",
                stateOverrides);

        assertTrue(graphNative.isSuccess());
        assertEquals("Hello Ada", graphNative.getOutputState().get("answer"));
    }

    @Test
    void graphNativeDebugRunExecutesAnswerNode() {
        LangGraph4jRuntimeAdapter adapter = adapter(null);
        GraphSpec graphSpec = GraphSpec.builder()
                .code("answer_graph")
                .entry("answer")
                .finishNode("answer")
                .node(GraphSpec.Node.builder()
                        .id("answer")
                        .type("ANSWER")
                        .config(Map.of("template", "Done: {{ params.task }}"))
                        .build())
                .edge(GraphSpec.Edge.builder().from("START").to("answer").condition("always").build())
                .edge(GraphSpec.Edge.builder().from("answer").to("END").condition("always").build())
                .build();
        Map<String, Object> inputParams = Map.of("task", "review");

        LangGraph4jRuntimeAdapter.WorkflowDebugRunResult graphNative = adapter.debugRun(
                graphSpec,
                defaultContext(),
                "run",
                inputParams,
                Map.of());

        assertTrue(graphNative.isSuccess());
        assertEquals("Done: review", graphNative.getAnswer());
        assertEquals(1, graphNative.getSteps().size());
    }

    @Test
    void graphNativeExecuteRunsGraph() {
        LangGraph4jRuntimeAdapter adapter = adapter(null);
        GraphSpec graphSpec = GraphSpec.builder()
                .code("answer_graph")
                .entry("answer")
                .finishNode("answer")
                .node(GraphSpec.Node.builder()
                        .id("answer")
                        .type("ANSWER")
                        .config(Map.of("template", "Done: {{ params.task }}"))
                        .build())
                .edge(GraphSpec.Edge.builder().from("START").to("answer").condition("always").build())
                .edge(GraphSpec.Edge.builder().from("answer").to("END").condition("always").build())
                .build();
        AgentRuntimeResult graphNative = adapter.execute(AgentRuntimeRequest.builder()
                .traceId("trace-answer")
                .sessionId("session-answer")
                .message("run")
                .runtimeOptions(Map.of("params", Map.of("task", "review")))
                .graphSpec(graphSpec)
                .graphRuntimeContext(defaultContext())
                .build());

        assertTrue(graphNative.isSuccess());
        assertEquals("Done: review", graphNative.getAnswer());
    }

    @Test
    void graphNativeExecuteCarriesWorkflowTraceMetadata() {
        LangGraph4jRuntimeAdapter adapter = adapter(null);
        GraphSpec graphSpec = GraphSpec.builder()
                .code("orders")
                .entry("answer")
                .finishNode("answer")
                .node(GraphSpec.Node.builder()
                        .id("answer")
                        .type("ANSWER")
                        .config(Map.of("template", "Done"))
                        .build())
                .edge(GraphSpec.Edge.builder().from("START").to("answer").condition("always").build())
                .edge(GraphSpec.Edge.builder().from("answer").to("END").condition("always").build())
                .build();
        GraphRuntimeContext runtimeContext = GraphRuntimeContext.builder()
                .sourceType("WORKFLOW_VERSION")
                .sourceId("wf-1")
                .sourceKeySlug("orders")
                .sourceVersion("v1")
                .sourceVersionId(9L)
                .name("Orders Workflow")
                .runtimeType(AgentRuntimeAdapter.LANGGRAPH4J_RUNTIME_TYPE)
                .extra(Map.of(
                        "workflowId", "wf-1",
                        "workflowKeySlug", "orders",
                        "workflowVersion", "v1",
                        "workflowVersionId", 9L,
                        "entryAgentId", "agent-1",
                        "entryAgentKeySlug", "global-agent"))
                .build();
        AgentRuntimeRequest request = AgentRuntimeRequest.builder()
                .traceId("trace-wf")
                .sessionId("session-wf")
                .message("run")
                .build();

        AgentRuntimeResult result = adapter.execute(graphSpec, runtimeContext, request);

        assertTrue(result.isSuccess());
        assertEquals("wf-1", result.getMetadata().get("workflowId"));
        assertEquals(9L, result.getMetadata().get("workflowVersionId"));
        assertEquals("agent-1", result.getMetadata().get("entryAgentId"));
        assertEquals("global-agent", result.getMetadata().get("entryAgentKeySlug"));
        assertEquals("WORKFLOW_VERSION", result.getMetadata().get("sourceType"));
        assertEquals("wf-1", result.getMetadata().get("sourceId"));
    }

    @Test
    void onlySupportsGraphWithSupportedNodes() {
        LangGraph4jRuntimeAdapter adapter = adapter(null);

        assertTrue(adapter.supports(request(singleLlmGraph("llm"))));
        assertFalse(adapter.supports(request(GraphSpec.builder()
                .entry("unsupported")
                .node(GraphSpec.Node.builder().id("unsupported").type("UNKNOWN").build())
                .build())));
    }

    @Test
    void executesVariableAndTemplateFlowNodes() {
        LangGraph4jRuntimeAdapter adapter = adapter(null);

        AgentRuntimeResult result = adapter.execute(AgentRuntimeRequest.builder()
                .traceId("trace-flow")
                .sessionId("session-flow")
                .message("hello")
                .graphSpec(GraphSpec.builder()
                                .code("flow_graph")
                                .entry("llm")
                                .finishNode("reply")
                                .node(GraphSpec.Node.builder()
                                        .id("llm")
                                        .type("LLM")
                                        .config(Map.of("modelInstanceId", "llm-1"))
                                        .build())
                                .node(GraphSpec.Node.builder()
                                        .id("vars")
                                        .type("VARIABLE_ASSIGN")
                                        .config(Map.of(
                                                "assignments", Map.of("summary", "lastOutput"),
                                                "outputAlias", "state"))
                                        .build())
                                .node(GraphSpec.Node.builder()
                                        .id("reply")
                                        .type("TEMPLATE")
                                        .config(Map.of("template", "Result: {{ state.summary }}"))
                                        .build())
                                .edge(GraphSpec.Edge.builder().from("START").to("llm").condition("always").build())
                                .edge(GraphSpec.Edge.builder().from("llm").to("vars").condition("always").build())
                                .edge(GraphSpec.Edge.builder().from("vars").to("reply").condition("always").build())
                                .edge(GraphSpec.Edge.builder().from("reply").to("END").condition("always").build())
                                .build()).graphRuntimeContext(GraphRuntimeContext.builder().runtimeType(AgentRuntimeAdapter.LANGGRAPH4J_RUNTIME_TYPE).modelInstanceId("llm-1").systemPrompt("You are helpful.").build())
                .build());

        assertTrue(result.isSuccess());
        assertEquals("Result: langgraph answer", result.getAnswer());
        assertTrue(result.getSteps().contains("Graph node: vars (VARIABLE_ASSIGN)"));
        assertTrue(result.getSteps().contains("Graph node: reply (TEMPLATE)"));
    }

    @Test
    void executesParameterAndKnowledgeRetrievalNodes() {
        ToolDefinitionService toolDefinitionService = mock(ToolDefinitionService.class);
        when(toolDefinitionService.executeTool(eq("search_knowledge"), any()))
                .thenReturn(Map.of("chunks", List.of("knowledge hit")));
        LangGraph4jRuntimeAdapter adapter = new LangGraph4jRuntimeAdapter(
                successModelClient(),
                toolDefinitionService,
                null,
                mock(AgentTraceSpanService.class),
                new ObjectMapper(),
                null,
                null,
                null);

        AgentRuntimeResult result = adapter.execute(AgentRuntimeRequest.builder()
                .traceId("trace-knowledge")
                .sessionId("session-knowledge")
                .message("order 1001")
                .graphSpec(GraphSpec.builder()
                                .code("knowledge_graph")
                                .entry("llm")
                                .finishNode("reply")
                                .node(GraphSpec.Node.builder()
                                        .id("llm")
                                        .type("LLM")
                                        .config(Map.of("modelInstanceId", "llm-1"))
                                        .build())
                                .node(GraphSpec.Node.builder()
                                        .id("params")
                                        .type("PARAMETER_EXTRACT")
                                        .config(Map.of(
                                                "parameters", Map.of("question", "input"),
                                                "outputAlias", "params"))
                                        .build())
                                .node(GraphSpec.Node.builder()
                                        .id("knowledge")
                                        .type("KNOWLEDGE_RETRIEVAL")
                                        .config(Map.of(
                                                "knowledgeBaseGroupId", "kb_order",
                                                "query", "params.question",
                                                "outputAlias", "knowledge"))
                                        .build())
                                .node(GraphSpec.Node.builder()
                                        .id("reply")
                                        .type("TEMPLATE")
                                        .config(Map.of("template", "Knowledge: {{ knowledge.chunks.0 }}"))
                                        .build())
                                .edge(GraphSpec.Edge.builder().from("START").to("llm").condition("always").build())
                                .edge(GraphSpec.Edge.builder().from("llm").to("params").condition("always").build())
                                .edge(GraphSpec.Edge.builder().from("params").to("knowledge").condition("always").build())
                                .edge(GraphSpec.Edge.builder().from("knowledge").to("reply").condition("always").build())
                                .edge(GraphSpec.Edge.builder().from("reply").to("END").condition("always").build())
                                .build()).graphRuntimeContext(GraphRuntimeContext.builder().runtimeType(AgentRuntimeAdapter.LANGGRAPH4J_RUNTIME_TYPE).modelInstanceId("llm-1").systemPrompt("You are helpful.").build())
                .build());

        assertTrue(result.isSuccess());
        assertEquals("Knowledge: knowledge hit", result.getAnswer());
        verify(toolDefinitionService).executeTool(eq("search_knowledge"), any());
    }

    @Test
    void executesClassifierCodeAggregatorAndAnswerNodes() {
        LangGraph4jRuntimeAdapter adapter = adapter(null);

        AgentRuntimeResult result = adapter.execute(AgentRuntimeRequest.builder()
                .traceId("trace-advanced")
                .sessionId("session-advanced")
                .message("please refund order 1001")
                .graphSpec(GraphSpec.builder()
                                .code("advanced_graph")
                                .entry("llm")
                                .finishNode("answer")
                                .node(GraphSpec.Node.builder()
                                        .id("llm")
                                        .type("LLM")
                                        .config(Map.of("modelInstanceId", "llm-1", "outputAlias", "draft"))
                                        .build())
                                .node(GraphSpec.Node.builder()
                                        .id("classify")
                                        .type("INTENT_CLASSIFIER")
                                        .config(Map.of(
                                                "inputExpression", "input",
                                                "classes", List.of(Map.of(
                                                        "id", "refund",
                                                        "label", "Refund",
                                                        "keywords", List.of("refund"))),
                                                "defaultRoute", "other"))
                                        .build())
                                .node(GraphSpec.Node.builder()
                                        .id("code")
                                        .type("CODE")
                                        .config(Map.of(
                                                "outputs", Map.of(
                                                        "intent", "lastRoute",
                                                        "draft", "draft")))
                                        .build())
                                .node(GraphSpec.Node.builder()
                                        .id("aggregate")
                                        .type("VARIABLE_AGGREGATOR")
                                        .config(Map.of(
                                                "aggregateMode", "object",
                                                "items", List.of(
                                                        Map.of("name", "intent", "source", "nodeOutput.code.intent"),
                                                        Map.of("name", "draft", "source", "nodeOutput.code.draft")),
                                                "outputAlias", "summary"))
                                        .build())
                                .node(GraphSpec.Node.builder()
                                        .id("answer")
                                        .type("ANSWER")
                                        .config(Map.of("template", "Intent {{ summary.intent }}: {{ summary.draft }}"))
                                        .build())
                                .edge(GraphSpec.Edge.builder().from("START").to("llm").condition("always").build())
                                .edge(GraphSpec.Edge.builder().from("llm").to("classify").condition("always").build())
                                .edge(GraphSpec.Edge.builder().from("classify").to("code").condition("route:refund").build())
                                .edge(GraphSpec.Edge.builder().from("code").to("aggregate").condition("always").build())
                                .edge(GraphSpec.Edge.builder().from("aggregate").to("answer").condition("always").build())
                                .edge(GraphSpec.Edge.builder().from("answer").to("END").condition("always").build())
                                .build()).graphRuntimeContext(GraphRuntimeContext.builder().runtimeType(AgentRuntimeAdapter.LANGGRAPH4J_RUNTIME_TYPE).modelInstanceId("llm-1").systemPrompt("You are helpful.").build())
                .build());

        assertTrue(result.isSuccess());
        assertEquals("Intent refund: langgraph answer", result.getAnswer());
    }

    @Test
    void intentClassifierLlmStrategyUsesValidRoute() {
        LangGraph4jRuntimeAdapter adapter = adapterWithModelClient(
                jsonModelClient("{\"route\":\"search\",\"confidence\":0.92,\"reason\":\"用户要求查询\"}"),
                null);

        AgentRuntimeResult result = adapter.execute(AgentRuntimeRequest.builder()
                .traceId("trace-classifier-llm")
                .sessionId("session-classifier-llm")
                .message("帮我查一下表格")
                .graphSpec(llmClassifierGraph("LLM", List.of()))
                .graphRuntimeContext(GraphRuntimeContext.builder()
                        .runtimeType(AgentRuntimeAdapter.LANGGRAPH4J_RUNTIME_TYPE)
                        .modelInstanceId("llm-1")
                        .build())
                .build());

        assertTrue(result.isSuccess());
        assertEquals("search path", result.getAnswer());
    }

    @Test
    void intentClassifierLlmStrategyFallsBackOnUnknownRoute() {
        LangGraph4jRuntimeAdapter adapter = adapterWithModelClient(
                jsonModelClient("{\"route\":\"unknown\",\"confidence\":0.95,\"reason\":\"unknown\"}"),
                null);

        AgentRuntimeResult result = adapter.execute(AgentRuntimeRequest.builder()
                .traceId("trace-classifier-llm-unknown")
                .sessionId("session-classifier-llm-unknown")
                .message("随便说点什么")
                .graphSpec(llmClassifierGraph("LLM", List.of()))
                .graphRuntimeContext(GraphRuntimeContext.builder()
                        .runtimeType(AgentRuntimeAdapter.LANGGRAPH4J_RUNTIME_TYPE)
                        .modelInstanceId("llm-1")
                        .build())
                .build());

        assertTrue(result.isSuccess());
        assertEquals("else path", result.getAnswer());
    }

    @Test
    void intentClassifierLlmStrategyFallsBackOnLowConfidence() {
        LangGraph4jRuntimeAdapter adapter = adapterWithModelClient(
                jsonModelClient("{\"route\":\"search\",\"confidence\":0.2,\"reason\":\"uncertain\"}"),
                null);

        AgentRuntimeResult result = adapter.execute(AgentRuntimeRequest.builder()
                .traceId("trace-classifier-llm-low-confidence")
                .sessionId("session-classifier-llm-low-confidence")
                .message("帮我查一下表格")
                .graphSpec(llmClassifierGraph("LLM", List.of()))
                .graphRuntimeContext(GraphRuntimeContext.builder()
                        .runtimeType(AgentRuntimeAdapter.LANGGRAPH4J_RUNTIME_TYPE)
                        .modelInstanceId("llm-1")
                        .build())
                .build());

        assertTrue(result.isSuccess());
        assertEquals("else path", result.getAnswer());
    }

    @Test
    void intentClassifierHybridSkipsLlmWhenKeywordMatches() {
        AtomicInteger callCount = new AtomicInteger();
        LangGraph4jRuntimeAdapter adapter = adapterWithModelClient(
                countingJsonModelClient(callCount, "{\"route\":\"search\",\"confidence\":0.92,\"reason\":\"query\"}"),
                null);

        AgentRuntimeResult result = adapter.execute(AgentRuntimeRequest.builder()
                .traceId("trace-classifier-hybrid-keyword")
                .sessionId("session-classifier-hybrid-keyword")
                .message("please refund order")
                .graphSpec(llmClassifierGraph("HYBRID", List.of("refund")))
                .graphRuntimeContext(GraphRuntimeContext.builder()
                        .runtimeType(AgentRuntimeAdapter.LANGGRAPH4J_RUNTIME_TYPE)
                        .modelInstanceId("llm-1")
                        .build())
                .build());

        assertTrue(result.isSuccess());
        assertEquals("refund path", result.getAnswer());
        assertEquals(0, callCount.get());
    }

    @Test
    void intentClassifierHybridUsesLlmWhenKeywordMisses() {
        AtomicInteger callCount = new AtomicInteger();
        LangGraph4jRuntimeAdapter adapter = adapterWithModelClient(
                countingJsonModelClient(callCount, "{\"route\":\"search\",\"confidence\":0.91,\"reason\":\"query\"}"),
                null);

        AgentRuntimeResult result = adapter.execute(AgentRuntimeRequest.builder()
                .traceId("trace-classifier-hybrid-llm")
                .sessionId("session-classifier-hybrid-llm")
                .message("帮我查一下表格")
                .graphSpec(llmClassifierGraph("HYBRID", List.of("refund")))
                .graphRuntimeContext(GraphRuntimeContext.builder()
                        .runtimeType(AgentRuntimeAdapter.LANGGRAPH4J_RUNTIME_TYPE)
                        .modelInstanceId("llm-1")
                        .build())
                .build());

        assertTrue(result.isSuccess());
        assertEquals("search path", result.getAnswer());
        assertEquals(1, callCount.get());
    }

    @Test
    void executesApprovalLoopDocumentAndKnowledgeWriteNodes() {
        LangGraph4jRuntimeAdapter adapter = adapter(null);

        AgentRuntimeResult result = adapter.execute(AgentRuntimeRequest.builder()
                .traceId("trace-human-loop")
                .sessionId("session-human-loop")
                .message("invoice: INV-1001")
                .graphSpec(GraphSpec.builder()
                                .code("human_loop_graph")
                                .entry("extract")
                                .finishNode("answer")
                                .node(GraphSpec.Node.builder()
                                        .id("extract")
                                        .type("DOCUMENT_EXTRACT")
                                        .config(Map.of(
                                                "sourceExpression", "input",
                                                "fields", List.of(Map.of("name", "invoiceNo", "source", "regex:invoice: (INV-\\d+)"))))
                                        .build())
                                .node(GraphSpec.Node.builder()
                                        .id("approval")
                                        .type("HUMAN_APPROVAL")
                                        .config(Map.of("prompt", "确认 {{ nodeOutput.extract.invoiceNo }}", "defaultRoute", "approved"))
                                        .build())
                                .node(GraphSpec.Node.builder()
                                        .id("loop")
                                        .type("LOOP")
                                        .config(Map.of("loopKey", "retry", "maxIterations", 2))
                                        .build())
                                .node(GraphSpec.Node.builder()
                                        .id("write")
                                        .type("KNOWLEDGE_WRITE")
                                        .config(Map.of(
                                                "knowledgeBaseCode", "kb-orders",
                                                "titleExpression", "const:确认记录",
                                                "contentExpression", "nodeOutput.extract.invoiceNo",
                                                "outputAlias", "writePayload"))
                                        .build())
                                .node(GraphSpec.Node.builder()
                                        .id("answer")
                                        .type("ANSWER")
                                        .config(Map.of("template", "写入 {{ writePayload.content }}"))
                                        .build())
                                .edge(GraphSpec.Edge.builder().from("START").to("extract").condition("always").build())
                                .edge(GraphSpec.Edge.builder().from("extract").to("approval").condition("always").build())
                                .edge(GraphSpec.Edge.builder().from("approval").to("loop").condition("route:approved").build())
                                .edge(GraphSpec.Edge.builder().from("loop").to("write").condition("route:continue").build())
                                .edge(GraphSpec.Edge.builder().from("write").to("answer").condition("always").build())
                                .edge(GraphSpec.Edge.builder().from("answer").to("END").condition("always").build())
                                .build()).graphRuntimeContext(GraphRuntimeContext.builder().runtimeType(AgentRuntimeAdapter.LANGGRAPH4J_RUNTIME_TYPE).modelInstanceId("llm-1").systemPrompt("You are helpful.").build())
                .build());

        assertTrue(result.isSuccess());
        assertEquals("写入 INV-1001", result.getAnswer());
    }

    @Test
    void humanApprovalNodeSuspendsWithPersistedUiRequest() {
        SkillInteractionMapper interactionMapper = mock(SkillInteractionMapper.class);
        LangGraph4jRuntimeAdapter adapter = new LangGraph4jRuntimeAdapter(
                successModelClient(),
                mock(ToolDefinitionService.class),
                null,
                mock(AgentTraceSpanService.class),
                new ObjectMapper(),
                null,
                null,
                interactionMapper);

        AgentRuntimeResult result = adapter.execute(AgentRuntimeRequest.builder()
                .traceId("trace-approval")
                .sessionId("session-approval")
                .userId("user-1")
                .message("approve payment")
                .graphSpec(GraphSpec.builder()
                                .code("approval_graph")
                                .entry("approval")
                                .finishNode("approval")
                                .node(GraphSpec.Node.builder()
                                        .id("approval")
                                        .type("HUMAN_APPROVAL")
                                        .config(Map.of("prompt", "请审批 {{ input }}", "timeoutSeconds", 300))
                                        .build())
                                .edge(GraphSpec.Edge.builder().from("START").to("approval").condition("always").build())
                                .edge(GraphSpec.Edge.builder().from("approval").to("END").condition("always").build())
                                .build()).graphRuntimeContext(GraphRuntimeContext.builder().runtimeType(AgentRuntimeAdapter.LANGGRAPH4J_RUNTIME_TYPE).modelInstanceId("llm-1").build())
                .build());

        assertTrue(result.isSuccess());
        assertNotNull(result.getUiRequest());
        assertEquals("confirm", result.getUiRequest().getComponent());
        assertEquals("流程已暂停，等待人工审批", result.getAnswer());
        verify(interactionMapper).insert(argThat((SkillInteractionEntity row) ->
                row != null
                        && "PENDING".equals(row.getStatus())
                        && "trace-approval".equals(row.getTraceId())
                        && row.getSkillName().startsWith("graph-approval:approval")));
    }

    @Test
    void resumesGraphFromHumanApprovalDecision() {
        LangGraph4jRuntimeAdapter adapter = adapter(mock(ToolCallLogService.class));
        GraphSpec graphSpec = GraphSpec.builder()
                .code("approval_resume_graph")
                .entry("approval")
                .finishNode("answer")
                .node(GraphSpec.Node.builder()
                        .id("approval")
                        .type("HUMAN_APPROVAL")
                        .config(Map.of("prompt", "approve {{ input }}"))
                        .build())
                .node(GraphSpec.Node.builder()
                        .id("answer")
                        .type("ANSWER")
                        .config(Map.of("template", "continued {{ lastRoute }}"))
                        .build())
                .edge(GraphSpec.Edge.builder().from("START").to("approval").condition("always").build())
                .edge(GraphSpec.Edge.builder().from("approval").to("answer").condition("route:approved").build())
                .edge(GraphSpec.Edge.builder().from("answer").to("END").condition("always").build())
                .build();

        AgentRuntimeResult result = adapter.resumeFromHumanApproval(
                AgentRuntimeRequest.builder()
                        .traceId("trace-resume")
                        .sessionId("session-resume")
                        .userId("user-1")
                        .graphSpec(graphSpec)
                        .graphRuntimeContext(defaultContext())
                        .build(),
                Map.of("input", "payment"),
                "approval",
                "approved");

        assertTrue(result.isSuccess());
        assertEquals("continued approved", result.getAnswer());
        assertEquals("approval", result.getMetadata().get("resumedFrom"));
        assertEquals("approved", result.getMetadata().get("approvalRoute"));
    }

    @Test
    void resumeFromHumanApprovalPrefersGraphSpecNativeRequest() {
        LangGraph4jRuntimeAdapter adapter = adapter(mock(ToolCallLogService.class));
        GraphSpec graphSpec = GraphSpec.builder()
                .code("approval_resume_graph_native")
                .entry("approval")
                .finishNode("answer")
                .node(GraphSpec.Node.builder()
                        .id("approval")
                        .type("HUMAN_APPROVAL")
                        .config(Map.of("prompt", "approve {{ input }}"))
                        .build())
                .node(GraphSpec.Node.builder()
                        .id("answer")
                        .type("ANSWER")
                        .config(Map.of("template", "native {{ lastRoute }}"))
                        .build())
                .edge(GraphSpec.Edge.builder().from("START").to("approval").condition("always").build())
                .edge(GraphSpec.Edge.builder().from("approval").to("answer").condition("route:approved").build())
                .edge(GraphSpec.Edge.builder().from("answer").to("END").condition("always").build())
                .build();
        GraphRuntimeContext runtimeContext = GraphRuntimeContext.builder()
                .sourceType("WORKFLOW_VERSION")
                .sourceId("wf-resume")
                .sourceKeySlug("wf-resume")
                .sourceVersion("v1")
                .name("Workflow Resume")
                .extra(Map.of(
                        "workflowId", "wf-resume",
                        "entryAgentId", "entry-1"))
                .build();

        AgentRuntimeResult result = adapter.resumeFromHumanApproval(
                AgentRuntimeRequest.builder()
                        .traceId("trace-resume-native")
                        .sessionId("session-resume-native")
                        .userId("user-1")
                        .graphSpec(graphSpec)
                        .graphRuntimeContext(runtimeContext)
                        .build(),
                Map.of("input", "payment"),
                "approval",
                "approved");

        assertTrue(result.isSuccess());
        assertEquals("native approved", result.getAnswer());
        assertEquals("WORKFLOW_VERSION", result.getMetadata().get("sourceType"));
        assertEquals("wf-resume", result.getMetadata().get("workflowId"));
    }

    @Test
    void persistsKnowledgeWriteNodeThroughSkillsService() {
        SkillsServiceClient skillsServiceClient = mock(SkillsServiceClient.class);
        when(skillsServiceClient.importKnowledgeChunks(any()))
                .thenReturn(new SkillsServiceClient.KnowledgeImportResult(200, "success", null));
        LangGraph4jRuntimeAdapter adapter = new LangGraph4jRuntimeAdapter(
                successModelClient(),
                mock(ToolDefinitionService.class),
                null,
                mock(AgentTraceSpanService.class),
                new ObjectMapper(),
                null,
                skillsServiceClient,
                null);

        AgentRuntimeResult result = adapter.execute(AgentRuntimeRequest.builder()
                .traceId("trace-knowledge-write")
                .sessionId("session-knowledge-write")
                .message("ship order 1001")
                .graphSpec(GraphSpec.builder()
                                .code("knowledge_write_graph")
                                .entry("write")
                                .finishNode("answer")
                                .node(GraphSpec.Node.builder()
                                        .id("write")
                                        .type("KNOWLEDGE_WRITE")
                                        .config(Map.of(
                                                "knowledgeBaseCode", "kb-orders",
                                                "titleExpression", "const:order-note",
                                                "contentExpression", "input",
                                                "outputAlias", "writePayload"))
                                        .build())
                                .node(GraphSpec.Node.builder()
                                        .id("answer")
                                        .type("ANSWER")
                                        .config(Map.of("template", "{{ writePayload.status }} {{ writePayload.fileName }}"))
                                        .build())
                                .edge(GraphSpec.Edge.builder().from("START").to("write").condition("always").build())
                                .edge(GraphSpec.Edge.builder().from("write").to("answer").condition("always").build())
                                .edge(GraphSpec.Edge.builder().from("answer").to("END").condition("always").build())
                                .build()).graphRuntimeContext(GraphRuntimeContext.builder().runtimeType(AgentRuntimeAdapter.LANGGRAPH4J_RUNTIME_TYPE).modelInstanceId("llm-1").build())
                .build());

        assertTrue(result.isSuccess());
        assertEquals("persisted order-note.md", result.getAnswer());
        verify(skillsServiceClient).importKnowledgeChunks(any(SkillsServiceClient.KnowledgeImportRequest.class));
    }

    @Test
    void llmNodeUsesLegacyPromptAsSystemPromptFallback() {
        AtomicReference<ModelServiceClient.ModelChatRequest> captured = new AtomicReference<>();
        LangGraph4jRuntimeAdapter adapter = new LangGraph4jRuntimeAdapter(
                capturingModelClient(captured),
                mock(ToolDefinitionService.class),
                null,
                mock(AgentTraceSpanService.class),
                new ObjectMapper(),
                null,
                null,
                null);

        AgentRuntimeResult result = adapter.execute(AgentRuntimeRequest.builder()
                .traceId("trace-legacy-prompt")
                .sessionId("session-legacy-prompt")
                .message("你好")
                .graphSpec(GraphSpec.builder()
                        .code("legacy_prompt_graph")
                        .entry("answer")
                        .finishNode("answer")
                        .node(GraphSpec.Node.builder()
                                .id("answer")
                                .type("LLM")
                                .config(Map.of(
                                        "modelInstanceId", "llm-1",
                                        "prompt", "Answer as the project page copilot."))
                                .build())
                        .edge(GraphSpec.Edge.builder().from("START").to("answer").condition("always").build())
                        .edge(GraphSpec.Edge.builder().from("answer").to("END").condition("always").build())
                        .build())
                .graphRuntimeContext(defaultContext())
                .build());

        assertTrue(result.isSuccess());
        assertEquals("system", captured.get().getMessages().get(0).getRole());
        assertEquals("Answer as the project page copilot.", captured.get().getMessages().get(0).getContent());
        assertEquals("你好", captured.get().getMessages().get(1).getContent());
    }

    private AgentRuntimeRequest request(GraphSpec graphSpec) {
        return AgentRuntimeRequest.builder()
                .traceId("trace")
                .message("hello")
                .graphSpec(graphSpec)
                .graphRuntimeContext(defaultContext())
                .build();
    }

    private GraphRuntimeContext defaultContext() {
        return GraphRuntimeContext.builder()
                .runtimeType(AgentRuntimeAdapter.LANGGRAPH4J_RUNTIME_TYPE)
                .modelInstanceId("llm-1")
                .build();
    }

    private LangGraph4jRuntimeAdapter adapter(ToolCallLogService logService) {
        return adapterWithModelClient(successModelClient(), logService);
    }

    private LangGraph4jRuntimeAdapter adapterWithModelClient(ModelServiceClient modelServiceClient,
                                                             ToolCallLogService logService) {
        return new LangGraph4jRuntimeAdapter(
                modelServiceClient,
                mock(ToolDefinitionService.class),
                logService,
                mock(AgentTraceSpanService.class),
                new ObjectMapper(),
                null,
                null,
                null);
    }

    private GraphSpec llmClassifierGraph(String strategy, List<String> keywords) {
        List<Map<String, Object>> classes = new ArrayList<>();
        classes.add(Map.of(
                "id", "search",
                "label", "查询",
                "description", "用户想查询或搜索",
                "keywords", List.of("查询", "搜索")));
        classes.add(Map.of(
                "id", "refund",
                "label", "退款",
                "description", "用户想退款",
                "keywords", keywords));
        Map<String, Object> classifierConfig = new LinkedHashMap<>();
        classifierConfig.put("strategy", strategy);
        classifierConfig.put("inputExpression", "input");
        classifierConfig.put("modelInstanceId", "llm-1");
        classifierConfig.put("confidenceThreshold", 0.7D);
        classifierConfig.put("classes", classes);
        classifierConfig.put("defaultRoute", "else");
        return GraphSpec.builder()
                .code("classifier_graph")
                .entry("classify")
                .finishNode("answer_search")
                .finishNode("answer_refund")
                .finishNode("answer_else")
                .node(GraphSpec.Node.builder()
                        .id("classify")
                        .type("INTENT_CLASSIFIER")
                        .config(classifierConfig)
                        .build())
                .node(GraphSpec.Node.builder()
                        .id("answer_search")
                        .type("ANSWER")
                        .config(Map.of("template", "search path"))
                        .build())
                .node(GraphSpec.Node.builder()
                        .id("answer_refund")
                        .type("ANSWER")
                        .config(Map.of("template", "refund path"))
                        .build())
                .node(GraphSpec.Node.builder()
                        .id("answer_else")
                        .type("ANSWER")
                        .config(Map.of("template", "else path"))
                        .build())
                .edge(GraphSpec.Edge.builder().from("START").to("classify").condition("always").build())
                .edge(GraphSpec.Edge.builder().from("classify").to("answer_search").condition("route:search").build())
                .edge(GraphSpec.Edge.builder().from("classify").to("answer_refund").condition("route:refund").build())
                .edge(GraphSpec.Edge.builder().from("classify").to("answer_else").condition("route:else").build())
                .edge(GraphSpec.Edge.builder().from("answer_search").to("END").condition("always").build())
                .edge(GraphSpec.Edge.builder().from("answer_refund").to("END").condition("always").build())
                .edge(GraphSpec.Edge.builder().from("answer_else").to("END").condition("always").build())
                .build();
    }

    private GraphSpec singleLlmGraph(String nodeId) {
        return GraphSpec.builder()
                .code("test_graph")
                .name("Test Graph")
                .runtimeHint(AgentRuntimeAdapter.LANGGRAPH4J_RUNTIME_TYPE)
                .entry(nodeId)
                .finishNode(nodeId)
                .node(GraphSpec.Node.builder()
                        .id(nodeId)
                        .type("LLM")
                        .name(nodeId)
                        .config(Map.of("modelInstanceId", "llm-1"))
                        .build())
                .edge(GraphSpec.Edge.builder().from("START").to(nodeId).condition("always").build())
                .edge(GraphSpec.Edge.builder().from(nodeId).to("END").condition("always").build())
                .build();
    }

    private GraphSpec pageAssistantChainGraph() {
        return GraphSpec.builder()
                .code("page-assistant-chain")
                .name("页面助手链路")
                .runtimeHint(AgentRuntimeAdapter.LANGGRAPH4J_RUNTIME_TYPE)
                .entry("extract_filters")
                .finishNode("answer")
                .node(GraphSpec.Node.builder()
                        .id("extract_filters")
                        .type("DOCUMENT_EXTRACT")
                        .name("抽取筛选条件")
                        .config(Map.of(
                                "sourceExpression", "input",
                                "outputAlias", "filters",
                                "fields", List.of(
                                        Map.of("name", "managerName", "type", "STRING",
                                                "source", "regex:(?:负责人|班组负责人)(?:为|是|叫|=|：|:)?[ ]*([^，。,. 的]+)"))))
                        .build())
                .node(GraphSpec.Node.builder()
                        .id("set_filters")
                        .type("PAGE_ACTION")
                        .name("设置筛选条件")
                        .config(Map.of(
                                "actionKey", "setFilters",
                                "title", "设置筛选条件",
                                "confirm", false,
                                "args", Map.of("managerName", "filters.managerName")))
                        .build())
                .node(GraphSpec.Node.builder()
                        .id("search")
                        .type("PAGE_ACTION")
                        .name("执行查询")
                        .config(Map.of(
                                "actionKey", "search",
                                "title", "执行查询",
                                "confirm", false))
                        .build())
                .node(GraphSpec.Node.builder()
                        .id("read_table")
                        .type("PAGE_ACTION")
                        .name("读取表格结果")
                        .config(Map.of(
                                "actionKey", "readTable",
                                "title", "读取表格结果",
                                "confirm", false))
                        .build())
                .node(GraphSpec.Node.builder()
                        .id("answer")
                        .type("ANSWER")
                        .name("返回结果")
                        .config(Map.of("template", "{{ lastOutput }}"))
                        .build())
                .edge(GraphSpec.Edge.builder().from("START").to("extract_filters").condition("always").build())
                .edge(GraphSpec.Edge.builder().from("extract_filters").to("set_filters").condition("always").build())
                .edge(GraphSpec.Edge.builder().from("set_filters").to("search").condition("always").build())
                .edge(GraphSpec.Edge.builder().from("search").to("read_table").condition("always").build())
                .edge(GraphSpec.Edge.builder().from("read_table").to("answer").condition("always").build())
                .edge(GraphSpec.Edge.builder().from("answer").to("END").condition("always").build())
                .build();
    }

    private GraphSpec parameterExtractSetFiltersGraph() {
        List<Map<String, Object>> fields = List.of(
                Map.of("name", "teamName", "type", "string", "description", "班组名称"),
                Map.of("name", "memberName", "type", "string", "description", "班组成员/成员"),
                Map.of("name", "organCode", "type", "array", "description", "关联组织/组织"),
                Map.of("name", "managerName", "type", "string", "description", "负责人/班组负责人/负责人员"));
        return GraphSpec.builder()
                .code("parameter-extract-set-filters")
                .name("参数抽取设置筛选")
                .runtimeHint(AgentRuntimeAdapter.LANGGRAPH4J_RUNTIME_TYPE)
                .entry("extract_filters")
                .finishNode("answer_query")
                .node(GraphSpec.Node.builder()
                        .id("extract_filters")
                        .type("PARAMETER_EXTRACT")
                        .name("LLM筛选提取")
                        .config(Map.of(
                                "extractMode", "llm",
                                "modelInstanceId", "llm-1",
                                "outputAlias", "extracted_filters",
                                "fields", fields))
                        .build())
                .node(GraphSpec.Node.builder()
                        .id("set_filters")
                        .type("PAGE_ACTION")
                        .name("设置筛选条件")
                        .config(Map.of(
                                "pageKey", "teamArchive.list",
                                "actionKey", "setFilters",
                                "args", Map.of(
                                        "teamName", "{{ nodeOutput.extract_filters.teamName }}",
                                        "memberName", "{{ nodeOutput.extract_filters.memberName }}",
                                        "organCode", "{{ nodeOutput.extract_filters.organCode }}",
                                        "managerName", "{{ nodeOutput.extract_filters.managerName }}")))
                        .build())
                .node(GraphSpec.Node.builder()
                        .id("search_action")
                        .type("PAGE_ACTION")
                        .name("执行查询")
                        .config(Map.of("pageKey", "teamArchive.list", "actionKey", "search", "args", Map.of()))
                        .build())
                .node(GraphSpec.Node.builder()
                        .id("read_table")
                        .type("PAGE_ACTION")
                        .name("读取表格")
                        .config(Map.of("pageKey", "teamArchive.list", "actionKey", "readTable", "args", Map.of()))
                        .build())
                .node(GraphSpec.Node.builder()
                        .id("answer_query")
                        .type("ANSWER")
                        .name("查询完成回复")
                        .config(Map.of("template", "已根据您的描述提取筛选条件并完成查询。"))
                        .build())
                .edge(GraphSpec.Edge.builder().from("START").to("extract_filters").condition("always").build())
                .edge(GraphSpec.Edge.builder().from("extract_filters").to("set_filters").condition("always").build())
                .edge(GraphSpec.Edge.builder().from("set_filters").to("search_action").condition("always").build())
                .edge(GraphSpec.Edge.builder().from("search_action").to("read_table").condition("always").build())
                .edge(GraphSpec.Edge.builder().from("read_table").to("answer_query").condition("always").build())
                .edge(GraphSpec.Edge.builder().from("answer_query").to("END").condition("always").build())
                .build();
    }

    private GraphSpec teamArchiveAssistantGraph() {
        return GraphSpec.builder()
                .code("team-archive-assistant")
                .name("班组档案助手")
                .runtimeHint(AgentRuntimeAdapter.LANGGRAPH4J_RUNTIME_TYPE)
                .entry("extract_filters")
                .finishNode("apply_search")
                .node(GraphSpec.Node.builder()
                        .id("extract_filters")
                        .type("DOCUMENT_EXTRACT")
                        .name("抽取班组筛选条件")
                        .config(Map.of(
                                "sourceExpression", "input",
                                "outputAlias", "filters",
                                "fields", List.of(
                                        Map.of("name", "managerName", "type", "STRING", "source", "regex:(?:负责人|负责人姓名)(?:为|是|叫|=|：|:)?[ ]*([^，。,. 的]+)"),
                                        Map.of("name", "teamName", "type", "STRING", "source", "regex:(?:班组名称|班组名|班组)(?:为|是|叫|=|：|:)[ ]*([^，。,. 的]+)"),
                                        Map.of("name", "memberName", "type", "STRING", "source", "regex:(?:班组成员|成员)(?:包含|包括|有|为|是|叫|=|：|:)?[ ]*([^，。,. 的]+)")
                                )))
                        .build())
                .node(GraphSpec.Node.builder()
                        .id("apply_search")
                        .type("PAGE_ACTION")
                        .name("执行班组档案页面查询")
                        .config(Map.of(
                                "actionKey", "qmssmp.teamArchive.search",
                                "title", "已按你的条件查询班组档案",
                                "confirm", false,
                                "args", Map.of(
                                        "managerName", "filters.managerName",
                                        "teamName", "filters.teamName",
                                        "memberName", "filters.memberName")))
                        .build())
                .edge(GraphSpec.Edge.builder().from("START").to("extract_filters").condition("always").build())
                .edge(GraphSpec.Edge.builder().from("extract_filters").to("apply_search").condition("always").build())
                .edge(GraphSpec.Edge.builder().from("apply_search").to("END").condition("always").build())
                .build();
    }

    private ModelServiceClient successModelClient() {
        return jsonModelClient("langgraph answer");
    }

    private ModelServiceClient capturingModelClient(AtomicReference<ModelServiceClient.ModelChatRequest> captured) {
        return new ModelServiceClient() {
            @Override
            public ModelChatResult chat(ModelChatRequest request) {
                captured.set(request);
                return new ModelChatResult(200, "ok", new ModelChatData(
                        "ok",
                        "qwen-plus",
                        "tongyi",
                        new ModelUsage(3, 4, 7),
                        null,
                        null,
                        "stop"));
            }

            @Override
            public ModelEmbeddingResult embed(ModelEmbeddingRequest request) {
                throw new UnsupportedOperationException();
            }

            @Override
            public ModelInstanceResult getModelInstance(String id) {
                return new ModelInstanceResult(200, "ok", new ModelInstanceData(
                        id,
                        "LLM",
                        "tongyi",
                        "LLM",
                        "qwen-plus",
                        null,
                        null,
                        Map.of(),
                        null,
                        "ACTIVE",
                        null));
            }

            @Override
            public ModelInstanceListResult listModelInstances(String modelType, String provider, String workspaceId) {
                return new ModelInstanceListResult(200, "ok", List.of());
            }
        };
    }

    private ModelServiceClient jsonModelClient(String content) {
        return new ModelServiceClient() {
            @Override
            public ModelChatResult chat(ModelChatRequest request) {
                return new ModelChatResult(200, "ok", new ModelChatData(
                        content,
                        "qwen-plus",
                        "tongyi",
                        new ModelUsage(3, 4, 7),
                        null,
                        null,
                        "stop"));
            }

            @Override
            public ModelEmbeddingResult embed(ModelEmbeddingRequest request) {
                throw new UnsupportedOperationException();
            }

            @Override
            public ModelInstanceResult getModelInstance(String id) {
                return new ModelInstanceResult(200, "ok", new ModelInstanceData(
                        id,
                        "LLM",
                        "tongyi",
                        "LLM",
                        "qwen-plus",
                        null,
                        null,
                        Map.of(),
                        null,
                        "ACTIVE",
                        null));
            }

            @Override
            public ModelInstanceListResult listModelInstances(String modelType, String provider, String workspaceId) {
                return new ModelInstanceListResult(200, "ok", List.of());
            }
        };
    }

    private ModelServiceClient countingJsonModelClient(AtomicInteger callCount, String content) {
        ModelServiceClient delegate = jsonModelClient(content);
        return new ModelServiceClient() {
            @Override
            public ModelChatResult chat(ModelChatRequest request) {
                callCount.incrementAndGet();
                return delegate.chat(request);
            }

            @Override
            public ModelEmbeddingResult embed(ModelEmbeddingRequest request) {
                return delegate.embed(request);
            }

            @Override
            public ModelInstanceResult getModelInstance(String id) {
                return delegate.getModelInstance(id);
            }

            @Override
            public ModelInstanceListResult listModelInstances(String modelType, String provider, String workspaceId) {
                return delegate.listModelInstances(modelType, provider, workspaceId);
            }
        };
    }
}
