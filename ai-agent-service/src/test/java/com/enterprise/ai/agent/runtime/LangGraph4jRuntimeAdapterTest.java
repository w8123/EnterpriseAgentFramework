package com.enterprise.ai.agent.runtime;

import com.enterprise.ai.agent.agent.AgentDefinition;
import com.enterprise.ai.agent.client.ModelServiceClient;
import com.enterprise.ai.agent.client.SkillsServiceClient;
import com.enterprise.ai.agent.graph.GraphSpec;
import com.enterprise.ai.agent.skill.interactive.SkillInteractionEntity;
import com.enterprise.ai.agent.skill.interactive.SkillInteractionMapper;
import com.enterprise.ai.agent.tool.log.ToolCallLogService;
import com.enterprise.ai.agent.tools.definition.ToolDefinitionService;
import com.enterprise.ai.agent.trace.AgentTraceSpanService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

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
    void executesUserInputNodeAndExposesParamsVariables() {
        LangGraph4jRuntimeAdapter adapter = adapter(null);

        AgentRuntimeResult result = adapter.execute(AgentRuntimeRequest.builder()
                .traceId("trace-user-input")
                .sessionId("session-user-input")
                .message("fallback question")
                .runtimeOptions(Map.of("params", Map.of(
                        "question", "How do I transfer?",
                        "upload_document", "file-001")))
                .agentDefinition(AgentDefinition.builder()
                        .id("agent-1")
                        .name("User Input Agent")
                        .type("single")
                        .runtimeType(AgentRuntimeAdapter.LANGGRAPH4J_RUNTIME_TYPE)
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
                                .build())
                        .build())
                .build());

        assertTrue(result.isSuccess());
        assertEquals("How do I transfer? / file-001", result.getAnswer());
        assertTrue(result.getMetadata().containsKey("graphNodes"));
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
                .agentDefinition(AgentDefinition.builder()
                        .id("agent-interaction")
                        .name("Interaction Agent")
                        .type("single")
                        .runtimeType(AgentRuntimeAdapter.LANGGRAPH4J_RUNTIME_TYPE)
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
                                .build())
                        .build())
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
                .agentDefinition(AgentDefinition.builder()
                        .id("agent-present-output")
                        .name("Present Output Agent")
                        .type("single")
                        .runtimeType(AgentRuntimeAdapter.LANGGRAPH4J_RUNTIME_TYPE)
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
                                .build())
                        .build())
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

        LangGraph4jRuntimeAdapter.WorkflowDebugRunResult result = adapter.debugRun(
                AgentDefinition.builder()
                        .id("agent-page-action")
                        .name("Page Action Agent")
                        .type("single")
                        .runtimeType(AgentRuntimeAdapter.LANGGRAPH4J_RUNTIME_TYPE)
                        .modelInstanceId("llm-1")
                        .graphSpec(GraphSpec.builder()
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
                                .build())
                        .build(),
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
    void interactionCollectInputPublishesExplicitTargetArgs() {
        LangGraph4jRuntimeAdapter adapter = adapter(null);

        AgentRuntimeResult result = adapter.execute(AgentRuntimeRequest.builder()
                .traceId("trace-interaction-slot")
                .sessionId("session-interaction-slot")
                .message("查询一下研发一组的班组信息")
                .runtimeOptions(Map.of("params", Map.of("teamName", "研发一组")))
                .agentDefinition(AgentDefinition.builder()
                        .id("agent-interaction-slot")
                        .name("Interaction Slot Agent")
                        .type("single")
                        .runtimeType(AgentRuntimeAdapter.LANGGRAPH4J_RUNTIME_TYPE)
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
                                .build())
                        .build())
                .build());

        assertTrue(result.isSuccess());
        assertEquals("研发一组", result.getAnswer());
    }

    @Test
    void interactionCollectInputDoesNotGuessRequiredFieldByDefault() {
        LangGraph4jRuntimeAdapter adapter = adapter(null);

        AgentDefinition definition = AgentDefinition.builder()
                .id("agent-interaction-no-guess")
                .name("Interaction No Guess Agent")
                .type("single")
                .runtimeType(AgentRuntimeAdapter.LANGGRAPH4J_RUNTIME_TYPE)
                .graphSpec(GraphSpec.builder()
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
                        .build())
                .build();

        LangGraph4jRuntimeAdapter.WorkflowDebugRunResult result = adapter.debugRun(
                definition,
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
                .agentDefinition(AgentDefinition.builder()
                        .id("agent-interaction-llm-slot")
                        .name("Interaction LLM Slot Agent")
                        .type("single")
                        .runtimeType(AgentRuntimeAdapter.LANGGRAPH4J_RUNTIME_TYPE)
                        .modelInstanceId("llm-1")
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
                                .build())
                        .build())
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
                .agentDefinition(AgentDefinition.builder()
                        .id("agent-interaction-enabled-user-input-slot")
                        .name("Interaction Enabled User Input Slot Agent")
                        .type("single")
                        .runtimeType(AgentRuntimeAdapter.LANGGRAPH4J_RUNTIME_TYPE)
                        .modelInstanceId("llm-1")
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
                                .build())
                        .build())
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

        AgentDefinition definition = AgentDefinition.builder()
                .id("agent-interaction-low-confidence")
                .name("Interaction Low Confidence Agent")
                .type("single")
                .runtimeType(AgentRuntimeAdapter.LANGGRAPH4J_RUNTIME_TYPE)
                .modelInstanceId("llm-1")
                .graphSpec(GraphSpec.builder()
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
                        .build())
                .build();

        LangGraph4jRuntimeAdapter.WorkflowDebugRunResult result = adapter.debugRun(
                definition,
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

        AgentDefinition definition = AgentDefinition.builder()
                .id("agent-interaction-wait")
                .name("Interaction Wait Agent")
                .type("single")
                .runtimeType(AgentRuntimeAdapter.LANGGRAPH4J_RUNTIME_TYPE)
                .graphSpec(GraphSpec.builder()
                        .code("interaction_wait_graph")
                        .entry("collect")
                        .finishNode("answer")
                        .node(GraphSpec.Node.builder()
                                .id("collect")
                                .type("INTERACTION")
                                .config(Map.of(
                                        "interactionType", "COLLECT_INPUT",
                                        "outputAlias", "profile",
                                        "fields", List.of(Map.of(
                                                "key", "deptId",
                                                "label", "部门 ID",
                                                "targetPath", "body_json.deptId",
                                                "type", "string",
                                                "required", true))))
                                .build())
                        .node(GraphSpec.Node.builder()
                                .id("answer")
                                .type("ANSWER")
                                .config(Map.of("template", "ok"))
                                .build())
                        .edge(GraphSpec.Edge.builder().from("START").to("collect").condition("always").build())
                        .edge(GraphSpec.Edge.builder().from("collect").to("answer").condition("always").build())
                        .edge(GraphSpec.Edge.builder().from("answer").to("END").condition("always").build())
                        .build())
                .build();

        LangGraph4jRuntimeAdapter.WorkflowDebugRunResult result = adapter.debugRun(
                definition,
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

        AgentDefinition definition = AgentDefinition.builder()
                .id("agent-vars")
                .name("Variable Contract Agent")
                .type("single")
                .runtimeType(AgentRuntimeAdapter.LANGGRAPH4J_RUNTIME_TYPE)
                .graphSpec(GraphSpec.builder()
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
                        .build())
                .build();

        LangGraph4jRuntimeAdapter.WorkflowDebugRunResult result = adapter.debugRun(
                definition,
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
                .agentDefinition(AgentDefinition.builder()
                        .id("agent-tool-mapping")
                        .name("Tool Mapping Agent")
                        .type("single")
                        .runtimeType(AgentRuntimeAdapter.LANGGRAPH4J_RUNTIME_TYPE)
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
                                .build())
                        .build())
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
                .agentDefinition(AgentDefinition.builder()
                        .id("agent-tool-timeout")
                        .name("Tool Timeout Agent")
                        .type("single")
                        .runtimeType(AgentRuntimeAdapter.LANGGRAPH4J_RUNTIME_TYPE)
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
                                .build())
                        .build())
                .build());

        assertTrue(result.isSuccess());
        verify(toolDefinitionService).executeTool(eq("slow_api"), any(), eq(Duration.ofMillis(180_000)));
    }

    @Test
    void debugRunReturnsWorkflowStepsAndBranchRoute() {
        LangGraph4jRuntimeAdapter adapter = adapter(null);

        AgentDefinition definition = AgentDefinition.builder()
                .id("agent-debug")
                .name("Debug Workflow")
                .type("single")
                .runtimeType(AgentRuntimeAdapter.LANGGRAPH4J_RUNTIME_TYPE)
                .graphSpec(GraphSpec.builder()
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
                                .name("回复不知道")
                                .config(Map.of("template", "我不知道"))
                                .build())
                        .edge(GraphSpec.Edge.builder().from("START").to("user_input").condition("always").build())
                        .edge(GraphSpec.Edge.builder().from("user_input").to("intent_classifier").condition("always").build())
                        .edge(GraphSpec.Edge.builder().from("intent_classifier").to("metro_answer").condition("route:metro_question").build())
                        .edge(GraphSpec.Edge.builder().from("intent_classifier").to("unknown_reply").condition("route:irrelevant").build())
                        .edge(GraphSpec.Edge.builder().from("metro_answer").to("END").condition("always").build())
                        .edge(GraphSpec.Edge.builder().from("unknown_reply").to("END").condition("always").build())
                        .build())
                .build();

        LangGraph4jRuntimeAdapter.WorkflowDebugRunResult result = adapter.debugRun(
                definition,
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
                .agentDefinition(AgentDefinition.builder()
                        .id("agent-llm-context-vars")
                        .name("LLM Context Vars Agent")
                        .type("single")
                        .runtimeType(AgentRuntimeAdapter.LANGGRAPH4J_RUNTIME_TYPE)
                        .modelInstanceId("llm-1")
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
                                .build())
                        .build())
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

        LangGraph4jRuntimeAdapter.NodeDebugResult result = adapter.debugNode(
                AgentDefinition.builder()
                        .id("agent-1")
                        .name("Debug Agent")
                        .type("single")
                        .runtimeType(AgentRuntimeAdapter.LANGGRAPH4J_RUNTIME_TYPE)
                        .modelInstanceId("llm-1")
                        .systemPrompt("You are helpful.")
                        .graphSpec(GraphSpec.builder()
                                .code("debug_graph")
                                .entry("reply")
                                .finishNode("reply")
                                .node(GraphSpec.Node.builder()
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
                .graphSpec(GraphSpec.builder()
                        .entry("not-llm")
                        .node(GraphSpec.Node.builder().id("not-llm").type("TOOL").build())
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
                .graphSpec(GraphSpec.builder()
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
