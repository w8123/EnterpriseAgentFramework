package com.enterprise.ai.agent.runtime;

import com.enterprise.ai.agent.agent.AgentDefinition;
import com.enterprise.ai.agent.client.ModelServiceClient;
import com.enterprise.ai.agent.client.SkillsServiceClient;
import com.enterprise.ai.agent.graph.AgentGraphSpec;
import com.enterprise.ai.agent.skill.interactive.SkillInteractionEntity;
import com.enterprise.ai.agent.skill.interactive.SkillInteractionMapper;
import com.enterprise.ai.agent.tool.log.ToolCallLogService;
import com.enterprise.ai.agent.tools.definition.ToolDefinitionService;
import com.enterprise.ai.agent.trace.AgentTraceSpanService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LangGraph4jRuntimeAdapterTest {

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
                .agentDefinition(AgentDefinition.builder()
                        .id("agent-1")
                        .name("LangGraph Agent")
                        .type("single")
                        .runtimeType(AgentRuntimeAdapter.LANGGRAPH4J_RUNTIME_TYPE)
                        .modelInstanceId("llm-1")
                        .systemPrompt("You are helpful.")
                        .graphSpec(singleLlmGraph("llm"))
                        .build())
                .build());

        assertTrue(result.isSuccess());
        assertEquals("langgraph answer", result.getAnswer());
        assertEquals(AgentRuntimeAdapter.LANGGRAPH4J_RUNTIME_TYPE, result.getRuntimeType());
        assertEquals("LangGraph Agent", result.getAgentName());
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
                .agentDefinition(AgentDefinition.builder()
                        .id("agent-1")
                        .name("LangGraph Agent")
                        .type("single")
                        .runtimeType(AgentRuntimeAdapter.LANGGRAPH4J_RUNTIME_TYPE)
                        .modelInstanceId("llm-1")
                        .systemPrompt("You are helpful.")
                        .graphSpec(singleLlmGraph("planner"))
                        .build())
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
                .agentDefinition(AgentDefinition.builder()
                        .id("agent-1")
                        .name("JSON Agent")
                        .type("single")
                        .runtimeType(AgentRuntimeAdapter.LANGGRAPH4J_RUNTIME_TYPE)
                        .modelInstanceId("llm-1")
                        .systemPrompt("You are helpful.")
                        .graphSpec(AgentGraphSpec.builder()
                                .code("json_graph")
                                .entry("llm")
                                .finishNode("reply")
                                .node(AgentGraphSpec.Node.builder()
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
                                .node(AgentGraphSpec.Node.builder()
                                        .id("reply")
                                        .type("TEMPLATE")
                                        .config(Map.of("template", "Order {{ parsed.orderId }} amount {{ parsed.amount }} status {{ parsed.status }}"))
                                        .build())
                                .edge(AgentGraphSpec.Edge.builder().from("START").to("llm").condition("always").build())
                                .edge(AgentGraphSpec.Edge.builder().from("llm").to("reply").condition("always").build())
                                .edge(AgentGraphSpec.Edge.builder().from("reply").to("END").condition("always").build())
                                .build())
                        .build())
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
                .agentDefinition(AgentDefinition.builder()
                        .id("agent-1")
                        .name("Message Agent")
                        .type("single")
                        .runtimeType(AgentRuntimeAdapter.LANGGRAPH4J_RUNTIME_TYPE)
                        .modelInstanceId("llm-1")
                        .graphSpec(AgentGraphSpec.builder()
                                .code("messages_graph")
                                .entry("llm")
                                .finishNode("llm")
                                .node(AgentGraphSpec.Node.builder()
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
                                .edge(AgentGraphSpec.Edge.builder().from("START").to("llm").condition("always").build())
                                .edge(AgentGraphSpec.Edge.builder().from("llm").to("END").condition("always").build())
                                .build())
                        .build())
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
    void debugsSingleTemplateNodeWithStateOverrides() {
        LangGraph4jRuntimeAdapter adapter = adapter(null);

        LangGraph4jRuntimeAdapter.NodeDebugResult result = adapter.debugNode(
                AgentDefinition.builder()
                        .id("agent-1")
                        .name("Debug Agent")
                        .type("single")
                        .runtimeType(AgentRuntimeAdapter.LANGGRAPH4J_RUNTIME_TYPE)
                        .modelInstanceId("llm-1")
                        .systemPrompt("You are helpful.")
                        .graphSpec(AgentGraphSpec.builder()
                                .code("debug_graph")
                                .entry("reply")
                                .finishNode("reply")
                                .node(AgentGraphSpec.Node.builder()
                                        .id("reply")
                                        .type("TEMPLATE")
                                        .config(Map.of("template", "Hello {{ customer.name }}", "writeToAnswer", true))
                                        .build())
                                .build())
                        .build(),
                "reply",
                "hello",
                Map.of("customer", Map.of("name", "Ada")));

        assertTrue(result.isSuccess());
        assertEquals("Hello Ada", result.getOutputState().get("answer"));
        assertEquals("Hello Ada", result.getNodeOutput());
    }

    @Test
    void onlySupportsSingleAgentWithoutToolsForMinimumRuntime() {
        LangGraph4jRuntimeAdapter adapter = adapter(null);

        assertTrue(adapter.supports(request(AgentDefinition.builder()
                .type("single")
                .modelInstanceId("llm-1")
                .graphSpec(singleLlmGraph("llm"))
                .build())));
        assertFalse(adapter.supports(request(AgentDefinition.builder()
                .type("pipeline")
                .modelInstanceId("llm-1")
                .graphSpec(singleLlmGraph("llm"))
                .build())));
        assertTrue(adapter.supports(request(AgentDefinition.builder()
                .type("single")
                .modelInstanceId("llm-1")
                .tools(List.of("query_team"))
                .graphSpec(singleLlmGraph("llm"))
                .build())));
        assertFalse(adapter.supports(request(AgentDefinition.builder()
                .type("single")
                .modelInstanceId("llm-1")
                .graphSpec(AgentGraphSpec.builder()
                        .entry("not-llm")
                        .node(AgentGraphSpec.Node.builder().id("not-llm").type("TOOL").build())
                        .build())
                .build())));
    }

    @Test
    void executesVariableAndTemplateFlowNodes() {
        LangGraph4jRuntimeAdapter adapter = adapter(null);

        AgentRuntimeResult result = adapter.execute(AgentRuntimeRequest.builder()
                .traceId("trace-flow")
                .sessionId("session-flow")
                .message("hello")
                .agentDefinition(AgentDefinition.builder()
                        .id("agent-1")
                        .name("Flow Agent")
                        .type("single")
                        .runtimeType(AgentRuntimeAdapter.LANGGRAPH4J_RUNTIME_TYPE)
                        .modelInstanceId("llm-1")
                        .systemPrompt("You are helpful.")
                        .graphSpec(AgentGraphSpec.builder()
                                .code("flow_graph")
                                .entry("llm")
                                .finishNode("reply")
                                .node(AgentGraphSpec.Node.builder()
                                        .id("llm")
                                        .type("LLM")
                                        .config(Map.of("modelInstanceId", "llm-1"))
                                        .build())
                                .node(AgentGraphSpec.Node.builder()
                                        .id("vars")
                                        .type("VARIABLE_ASSIGN")
                                        .config(Map.of(
                                                "assignments", Map.of("summary", "lastOutput"),
                                                "outputAlias", "state"))
                                        .build())
                                .node(AgentGraphSpec.Node.builder()
                                        .id("reply")
                                        .type("TEMPLATE")
                                        .config(Map.of("template", "Result: {{ state.summary }}"))
                                        .build())
                                .edge(AgentGraphSpec.Edge.builder().from("START").to("llm").condition("always").build())
                                .edge(AgentGraphSpec.Edge.builder().from("llm").to("vars").condition("always").build())
                                .edge(AgentGraphSpec.Edge.builder().from("vars").to("reply").condition("always").build())
                                .edge(AgentGraphSpec.Edge.builder().from("reply").to("END").condition("always").build())
                                .build())
                        .build())
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
                .agentDefinition(AgentDefinition.builder()
                        .id("agent-1")
                        .name("Knowledge Agent")
                        .type("single")
                        .runtimeType(AgentRuntimeAdapter.LANGGRAPH4J_RUNTIME_TYPE)
                        .modelInstanceId("llm-1")
                        .systemPrompt("You are helpful.")
                        .graphSpec(AgentGraphSpec.builder()
                                .code("knowledge_graph")
                                .entry("llm")
                                .finishNode("reply")
                                .node(AgentGraphSpec.Node.builder()
                                        .id("llm")
                                        .type("LLM")
                                        .config(Map.of("modelInstanceId", "llm-1"))
                                        .build())
                                .node(AgentGraphSpec.Node.builder()
                                        .id("params")
                                        .type("PARAMETER_EXTRACT")
                                        .config(Map.of(
                                                "parameters", Map.of("question", "input"),
                                                "outputAlias", "params"))
                                        .build())
                                .node(AgentGraphSpec.Node.builder()
                                        .id("knowledge")
                                        .type("KNOWLEDGE_RETRIEVAL")
                                        .config(Map.of(
                                                "knowledgeBaseGroupId", "kb_order",
                                                "query", "params.question",
                                                "outputAlias", "knowledge"))
                                        .build())
                                .node(AgentGraphSpec.Node.builder()
                                        .id("reply")
                                        .type("TEMPLATE")
                                        .config(Map.of("template", "Knowledge: {{ knowledge.chunks.0 }}"))
                                        .build())
                                .edge(AgentGraphSpec.Edge.builder().from("START").to("llm").condition("always").build())
                                .edge(AgentGraphSpec.Edge.builder().from("llm").to("params").condition("always").build())
                                .edge(AgentGraphSpec.Edge.builder().from("params").to("knowledge").condition("always").build())
                                .edge(AgentGraphSpec.Edge.builder().from("knowledge").to("reply").condition("always").build())
                                .edge(AgentGraphSpec.Edge.builder().from("reply").to("END").condition("always").build())
                                .build())
                        .build())
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
                .agentDefinition(AgentDefinition.builder()
                        .id("agent-1")
                        .name("Advanced Nodes Agent")
                        .type("single")
                        .runtimeType(AgentRuntimeAdapter.LANGGRAPH4J_RUNTIME_TYPE)
                        .modelInstanceId("llm-1")
                        .systemPrompt("You are helpful.")
                        .graphSpec(AgentGraphSpec.builder()
                                .code("advanced_graph")
                                .entry("llm")
                                .finishNode("answer")
                                .node(AgentGraphSpec.Node.builder()
                                        .id("llm")
                                        .type("LLM")
                                        .config(Map.of("modelInstanceId", "llm-1", "outputAlias", "draft"))
                                        .build())
                                .node(AgentGraphSpec.Node.builder()
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
                                .node(AgentGraphSpec.Node.builder()
                                        .id("code")
                                        .type("CODE")
                                        .config(Map.of(
                                                "outputs", Map.of(
                                                        "intent", "lastRoute",
                                                        "draft", "draft")))
                                        .build())
                                .node(AgentGraphSpec.Node.builder()
                                        .id("aggregate")
                                        .type("VARIABLE_AGGREGATOR")
                                        .config(Map.of(
                                                "aggregateMode", "object",
                                                "items", List.of(
                                                        Map.of("name", "intent", "source", "nodeOutput.code.intent"),
                                                        Map.of("name", "draft", "source", "nodeOutput.code.draft")),
                                                "outputAlias", "summary"))
                                        .build())
                                .node(AgentGraphSpec.Node.builder()
                                        .id("answer")
                                        .type("ANSWER")
                                        .config(Map.of("template", "Intent {{ summary.intent }}: {{ summary.draft }}"))
                                        .build())
                                .edge(AgentGraphSpec.Edge.builder().from("START").to("llm").condition("always").build())
                                .edge(AgentGraphSpec.Edge.builder().from("llm").to("classify").condition("always").build())
                                .edge(AgentGraphSpec.Edge.builder().from("classify").to("code").condition("route:refund").build())
                                .edge(AgentGraphSpec.Edge.builder().from("code").to("aggregate").condition("always").build())
                                .edge(AgentGraphSpec.Edge.builder().from("aggregate").to("answer").condition("always").build())
                                .edge(AgentGraphSpec.Edge.builder().from("answer").to("END").condition("always").build())
                                .build())
                        .build())
                .build());

        assertTrue(result.isSuccess());
        assertEquals("Intent refund: langgraph answer", result.getAnswer());
    }

    @Test
    void executesApprovalLoopDocumentAndKnowledgeWriteNodes() {
        LangGraph4jRuntimeAdapter adapter = adapter(null);

        AgentRuntimeResult result = adapter.execute(AgentRuntimeRequest.builder()
                .traceId("trace-human-loop")
                .sessionId("session-human-loop")
                .message("invoice: INV-1001")
                .agentDefinition(AgentDefinition.builder()
                        .id("agent-1")
                        .name("Human Loop Agent")
                        .type("single")
                        .runtimeType(AgentRuntimeAdapter.LANGGRAPH4J_RUNTIME_TYPE)
                        .modelInstanceId("llm-1")
                        .systemPrompt("You are helpful.")
                        .graphSpec(AgentGraphSpec.builder()
                                .code("human_loop_graph")
                                .entry("extract")
                                .finishNode("answer")
                                .node(AgentGraphSpec.Node.builder()
                                        .id("extract")
                                        .type("DOCUMENT_EXTRACT")
                                        .config(Map.of(
                                                "sourceExpression", "input",
                                                "fields", List.of(Map.of("name", "invoiceNo", "source", "regex:invoice: (INV-\\d+)"))))
                                        .build())
                                .node(AgentGraphSpec.Node.builder()
                                        .id("approval")
                                        .type("HUMAN_APPROVAL")
                                        .config(Map.of("prompt", "确认 {{ nodeOutput.extract.invoiceNo }}", "defaultRoute", "approved"))
                                        .build())
                                .node(AgentGraphSpec.Node.builder()
                                        .id("loop")
                                        .type("LOOP")
                                        .config(Map.of("loopKey", "retry", "maxIterations", 2))
                                        .build())
                                .node(AgentGraphSpec.Node.builder()
                                        .id("write")
                                        .type("KNOWLEDGE_WRITE")
                                        .config(Map.of(
                                                "knowledgeBaseCode", "kb-orders",
                                                "titleExpression", "const:确认记录",
                                                "contentExpression", "nodeOutput.extract.invoiceNo",
                                                "outputAlias", "writePayload"))
                                        .build())
                                .node(AgentGraphSpec.Node.builder()
                                        .id("answer")
                                        .type("ANSWER")
                                        .config(Map.of("template", "写入 {{ writePayload.content }}"))
                                        .build())
                                .edge(AgentGraphSpec.Edge.builder().from("START").to("extract").condition("always").build())
                                .edge(AgentGraphSpec.Edge.builder().from("extract").to("approval").condition("always").build())
                                .edge(AgentGraphSpec.Edge.builder().from("approval").to("loop").condition("route:approved").build())
                                .edge(AgentGraphSpec.Edge.builder().from("loop").to("write").condition("route:continue").build())
                                .edge(AgentGraphSpec.Edge.builder().from("write").to("answer").condition("always").build())
                                .edge(AgentGraphSpec.Edge.builder().from("answer").to("END").condition("always").build())
                                .build())
                        .build())
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
                .agentDefinition(AgentDefinition.builder()
                        .id("1")
                        .name("Approval Agent")
                        .type("single")
                        .runtimeType(AgentRuntimeAdapter.LANGGRAPH4J_RUNTIME_TYPE)
                        .modelInstanceId("llm-1")
                        .graphSpec(AgentGraphSpec.builder()
                                .code("approval_graph")
                                .entry("approval")
                                .finishNode("approval")
                                .node(AgentGraphSpec.Node.builder()
                                        .id("approval")
                                        .type("HUMAN_APPROVAL")
                                        .config(Map.of("prompt", "请审批 {{ input }}", "timeoutSeconds", 300))
                                        .build())
                                .edge(AgentGraphSpec.Edge.builder().from("START").to("approval").condition("always").build())
                                .edge(AgentGraphSpec.Edge.builder().from("approval").to("END").condition("always").build())
                                .build())
                        .build())
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
        AgentDefinition definition = AgentDefinition.builder()
                .id("2")
                .name("Approval Resume Agent")
                .type("single")
                .runtimeType(AgentRuntimeAdapter.LANGGRAPH4J_RUNTIME_TYPE)
                .modelInstanceId("llm-1")
                .graphSpec(AgentGraphSpec.builder()
                        .code("approval_resume_graph")
                        .entry("approval")
                        .finishNode("answer")
                        .node(AgentGraphSpec.Node.builder()
                                .id("approval")
                                .type("HUMAN_APPROVAL")
                                .config(Map.of("prompt", "approve {{ input }}"))
                                .build())
                        .node(AgentGraphSpec.Node.builder()
                                .id("answer")
                                .type("ANSWER")
                                .config(Map.of("template", "continued {{ lastRoute }}"))
                                .build())
                        .edge(AgentGraphSpec.Edge.builder().from("START").to("approval").condition("always").build())
                        .edge(AgentGraphSpec.Edge.builder().from("approval").to("answer").condition("route:approved").build())
                        .edge(AgentGraphSpec.Edge.builder().from("answer").to("END").condition("always").build())
                        .build())
                .build();

        AgentRuntimeResult result = adapter.resumeFromHumanApproval(
                AgentRuntimeRequest.builder()
                        .traceId("trace-resume")
                        .sessionId("session-resume")
                        .userId("user-1")
                        .agentDefinition(definition)
                        .build(),
                definition,
                Map.of("input", "payment"),
                "approval",
                "approved");

        assertTrue(result.isSuccess());
        assertEquals("continued approved", result.getAnswer());
        assertEquals("approval", result.getMetadata().get("resumedFrom"));
        assertEquals("approved", result.getMetadata().get("approvalRoute"));
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
                .agentDefinition(AgentDefinition.builder()
                        .id("agent-1")
                        .name("Knowledge Write Agent")
                        .type("single")
                        .runtimeType(AgentRuntimeAdapter.LANGGRAPH4J_RUNTIME_TYPE)
                        .modelInstanceId("llm-1")
                        .graphSpec(AgentGraphSpec.builder()
                                .code("knowledge_write_graph")
                                .entry("write")
                                .finishNode("answer")
                                .node(AgentGraphSpec.Node.builder()
                                        .id("write")
                                        .type("KNOWLEDGE_WRITE")
                                        .config(Map.of(
                                                "knowledgeBaseCode", "kb-orders",
                                                "titleExpression", "const:order-note",
                                                "contentExpression", "input",
                                                "outputAlias", "writePayload"))
                                        .build())
                                .node(AgentGraphSpec.Node.builder()
                                        .id("answer")
                                        .type("ANSWER")
                                        .config(Map.of("template", "{{ writePayload.status }} {{ writePayload.fileName }}"))
                                        .build())
                                .edge(AgentGraphSpec.Edge.builder().from("START").to("write").condition("always").build())
                                .edge(AgentGraphSpec.Edge.builder().from("write").to("answer").condition("always").build())
                                .edge(AgentGraphSpec.Edge.builder().from("answer").to("END").condition("always").build())
                                .build())
                        .build())
                .build());

        assertTrue(result.isSuccess());
        assertEquals("persisted order-note.md", result.getAnswer());
        verify(skillsServiceClient).importKnowledgeChunks(any(SkillsServiceClient.KnowledgeImportRequest.class));
    }

    private AgentRuntimeRequest request(AgentDefinition definition) {
        return AgentRuntimeRequest.builder()
                .traceId("trace")
                .message("hello")
                .agentDefinition(definition)
                .build();
    }

    private LangGraph4jRuntimeAdapter adapter(ToolCallLogService logService) {
        return new LangGraph4jRuntimeAdapter(
                successModelClient(),
                mock(ToolDefinitionService.class),
                logService,
                mock(AgentTraceSpanService.class),
                new ObjectMapper(),
                null,
                null,
                null);
    }

    private AgentGraphSpec singleLlmGraph(String nodeId) {
        return AgentGraphSpec.builder()
                .code("test_graph")
                .name("Test Graph")
                .runtimeHint(AgentRuntimeAdapter.LANGGRAPH4J_RUNTIME_TYPE)
                .entry(nodeId)
                .finishNode(nodeId)
                .node(AgentGraphSpec.Node.builder()
                        .id(nodeId)
                        .type("LLM")
                        .name(nodeId)
                        .config(Map.of("modelInstanceId", "llm-1"))
                        .build())
                .edge(AgentGraphSpec.Edge.builder().from("START").to(nodeId).condition("always").build())
                .edge(AgentGraphSpec.Edge.builder().from(nodeId).to("END").condition("always").build())
                .build();
    }

    private ModelServiceClient successModelClient() {
        return jsonModelClient("langgraph answer");
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
        };
    }
}
