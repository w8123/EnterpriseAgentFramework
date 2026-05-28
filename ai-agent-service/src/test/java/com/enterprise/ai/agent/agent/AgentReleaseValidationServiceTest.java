package com.enterprise.ai.agent.agent;

import com.enterprise.ai.agent.graph.GraphSpec;
import com.enterprise.ai.agent.graph.AgentGraphNodeType;
import com.enterprise.ai.agent.runtime.AgentRuntimeSelector;
import com.enterprise.ai.agent.runtime.AgentRuntimeValidationResult;
import com.enterprise.ai.agent.tools.definition.ToolDefinitionEntity;
import com.enterprise.ai.agent.tools.definition.ToolDefinitionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AgentReleaseValidationServiceTest {

    private AgentRuntimeSelector runtimeSelector;
    private ToolDefinitionService toolDefinitionService;
    private AgentReleaseValidationService service;

    @BeforeEach
    void setUp() {
        runtimeSelector = mock(AgentRuntimeSelector.class);
        toolDefinitionService = mock(ToolDefinitionService.class);
        service = new AgentReleaseValidationService(runtimeSelector, toolDefinitionService);
        when(runtimeSelector.validate(any())).thenReturn(AgentRuntimeValidationResult.builder()
                .valid(true)
                .build());
    }

    @Test
    void validateAcceptsPublishableGraph() {
        ToolDefinitionEntity tool = new ToolDefinitionEntity();
        tool.setName("query_order");
        tool.setKind("TOOL");
        tool.setEnabled(true);
        tool.setAgentVisible(true);
        tool.setDraft(false);
        tool.setVisibility("PUBLIC");
        when(toolDefinitionService.findByName("query_order")).thenReturn(Optional.of(tool));

        AgentReleaseValidationResult result = service.validate(baseDefinition(GraphSpec.builder()
                .entry("llm")
                .finishNode("tool")
                .node(GraphSpec.Node.builder().id("llm").type("LLM").build())
                .node(GraphSpec.Node.builder()
                        .id("tool")
                        .type("TOOL")
                        .ref(GraphSpec.CapabilityRef.builder().name("query_order").build())
                        .config(Map.of("inputMapping", Map.of("orderId", "$input")))
                        .build())
                .edge(GraphSpec.Edge.builder().from("llm").to("tool").condition("success").build())
                .build()));

        assertTrue(result.valid(), () -> result.errors().toString());
        assertTrue(result.errors().isEmpty());
    }

    @Test
    void validateAcceptsSdkGraphWithStartAndEndPseudoNodes() {
        AgentReleaseValidationResult result = service.validate(baseDefinition(GraphSpec.builder()
                .entry("llm")
                .finishNode("llm")
                .node(GraphSpec.Node.builder()
                        .id("llm")
                        .type("LLM")
                        .config(Map.of(
                                "outputFormat", "json",
                                "outputSchema", List.of(Map.of(
                                        "name", "answer",
                                        "type", "string",
                                        "required", true))))
                        .build())
                .edge(GraphSpec.Edge.builder().from("START").to("llm").condition("always").build())
                .edge(GraphSpec.Edge.builder().from("llm").to("END").condition("always").build())
                .build()));

        assertTrue(result.valid(), () -> result.errors().toString());
        assertTrue(result.errors().isEmpty());
    }

    @Test
    void validateRejectsInvalidLlmOutputSchema() {
        AgentReleaseValidationResult result = service.validate(baseDefinition(GraphSpec.builder()
                .entry("llm")
                .finishNode("llm")
                .node(GraphSpec.Node.builder()
                        .id("llm")
                        .type("LLM")
                        .config(Map.of(
                                "outputFormat", "xml",
                                "outputSchema", List.of(Map.of("name", "answer", "type", "date"))))
                        .build())
                .edge(GraphSpec.Edge.builder().from("START").to("llm").condition("always").build())
                .edge(GraphSpec.Edge.builder().from("llm").to("END").condition("always").build())
                .build()));

        assertFalse(result.valid());
        List<String> codes = result.errors().stream().map(AgentReleaseValidationResult.Item::code).toList();
        assertTrue(codes.contains("GRAPH_LLM_OUTPUT_FORMAT_INVALID"));
        assertTrue(codes.contains("GRAPH_PARAMETER_FIELD_TYPE_INVALID"));
    }

    @Test
    void validateAcceptsFlowPrimitiveNodes() {
        AgentReleaseValidationResult result = service.validate(baseDefinition(GraphSpec.builder()
                .entry("llm")
                .finishNode("reply")
                .node(GraphSpec.Node.builder().id("llm").type("LLM").build())
                .node(GraphSpec.Node.builder()
                        .id("vars")
                        .type("VARIABLE_ASSIGN")
                        .config(Map.of("assignments", Map.of("summary", "lastOutput")))
                        .build())
                .node(GraphSpec.Node.builder()
                        .id("route")
                        .type("IF_ELSE")
                        .config(Map.of("conditionGroups", List.of(Map.of(
                                "id", "nonempty",
                                "logic", "AND",
                                "conditions", List.of(Map.of(
                                        "left", "lastOutput",
                                        "operator", "not_empty",
                                        "right", ""))))))
                        .build())
                .node(GraphSpec.Node.builder()
                        .id("reply")
                        .type("TEMPLATE")
                        .config(Map.of("template", "{{ summary }}"))
                        .build())
                .edge(GraphSpec.Edge.builder().from("START").to("llm").condition("always").build())
                .edge(GraphSpec.Edge.builder().from("llm").to("vars").condition("always").build())
                .edge(GraphSpec.Edge.builder().from("vars").to("route").condition("always").build())
                .edge(GraphSpec.Edge.builder().from("route").to("reply").condition("route:nonempty").build())
                .edge(GraphSpec.Edge.builder().from("reply").to("END").condition("always").build())
                .build()));

        assertTrue(result.valid(), () -> result.errors().toString());
        assertTrue(result.errors().isEmpty());
    }

    @Test
    void validateAcceptsIntegrationAndRetrievalNodes() {
        AgentReleaseValidationResult result = service.validate(baseDefinition(GraphSpec.builder()
                .entry("llm")
                .finishNode("knowledge")
                .node(GraphSpec.Node.builder().id("llm").type("LLM").build())
                .node(GraphSpec.Node.builder()
                        .id("extract")
                        .type("PARAMETER_EXTRACT")
                        .config(Map.of("extractMode", "expression", "fields", List.of(Map.of(
                                "name", "orderId",
                                "type", "string",
                                "required", true,
                                "source", "lastOutput.orderId"))))
                        .build())
                .node(GraphSpec.Node.builder()
                        .id("api")
                        .type("HTTP_REQUEST")
                        .config(Map.of("method", "POST", "url", "https://example.com/api"))
                        .build())
                .node(GraphSpec.Node.builder()
                        .id("knowledge")
                        .type("KNOWLEDGE_RETRIEVAL")
                        .config(Map.of("knowledgeBaseCodes", List.of("kb_order"), "query", "input"))
                        .build())
                .edge(GraphSpec.Edge.builder().from("START").to("llm").condition("always").build())
                .edge(GraphSpec.Edge.builder().from("llm").to("extract").condition("always").build())
                .edge(GraphSpec.Edge.builder().from("extract").to("api").condition("always").build())
                .edge(GraphSpec.Edge.builder().from("api").to("knowledge").condition("success").build())
                .edge(GraphSpec.Edge.builder().from("knowledge").to("END").condition("always").build())
                .build()));

        assertTrue(result.valid(), () -> result.errors().toString());
        assertTrue(result.errors().isEmpty());
    }

    @Test
    void validateAcceptsPageActionNodeWithActionKey() {
        AgentReleaseValidationResult result = service.validate(baseDefinition(GraphSpec.builder()
                .entry("llm")
                .finishNode("open_detail")
                .node(GraphSpec.Node.builder().id("llm").type("LLM").build())
                .node(GraphSpec.Node.builder()
                        .id("open_detail")
                        .type("PAGE_ACTION")
                        .config(Map.of(
                                "actionKey", "team.openDetail",
                                "title", "Open team detail",
                                "confirm", true,
                                "args", Map.of("teamId", "lastOutput.id")))
                        .build())
                .edge(GraphSpec.Edge.builder().from("START").to("llm").condition("always").build())
                .edge(GraphSpec.Edge.builder().from("llm").to("open_detail").condition("always").build())
                .edge(GraphSpec.Edge.builder().from("open_detail").to("END").condition("always").build())
                .build()));

        assertTrue(result.valid(), () -> result.errors().toString());
    }

    @Test
    void validateRejectsPageActionWithoutActionKey() {
        AgentReleaseValidationResult result = service.validate(baseDefinition(GraphSpec.builder()
                .entry("llm")
                .finishNode("open_detail")
                .node(GraphSpec.Node.builder().id("llm").type("LLM").build())
                .node(GraphSpec.Node.builder()
                        .id("open_detail")
                        .type("PAGE_ACTION")
                        .config(Map.of("args", Map.of("teamId", "lastOutput.id")))
                        .build())
                .edge(GraphSpec.Edge.builder().from("START").to("llm").condition("always").build())
                .edge(GraphSpec.Edge.builder().from("llm").to("open_detail").condition("always").build())
                .edge(GraphSpec.Edge.builder().from("open_detail").to("END").condition("always").build())
                .build()));

        assertFalse(result.valid());
        List<String> codes = result.errors().stream().map(AgentReleaseValidationResult.Item::code).toList();
        assertTrue(codes.contains("GRAPH_PAGE_ACTION_KEY_EMPTY"));
    }

    @Test
    void validateAcceptsInteractionNodeWithKeyFields() {
        AgentReleaseValidationResult result = service.validate(baseDefinition(GraphSpec.builder()
                .entry("llm")
                .finishNode("reply")
                .node(GraphSpec.Node.builder().id("llm").type("LLM").build())
                .node(GraphSpec.Node.builder()
                        .id("collect_profile")
                        .type("INTERACTION")
                        .config(Map.of(
                                "interactionType", "COLLECT_INPUT",
                                "outputAlias", "profile",
                                "fields", List.of(
                                        Map.of("key", "age", "type", "integer", "required", true),
                                        Map.of("key", "chronicDisease", "type", "string", "required", false))))
                        .build())
                .node(GraphSpec.Node.builder()
                        .id("reply")
                        .type("ANSWER")
                        .config(Map.of("template", "{{ profile }}"))
                        .build())
                .edge(GraphSpec.Edge.builder().from("START").to("llm").condition("always").build())
                .edge(GraphSpec.Edge.builder().from("llm").to("collect_profile").condition("always").build())
                .edge(GraphSpec.Edge.builder().from("collect_profile").to("reply").condition("success").build())
                .edge(GraphSpec.Edge.builder().from("reply").to("END").condition("always").build())
                .build()));

        assertTrue(result.valid(), () -> result.errors().toString());
        assertTrue(result.errors().isEmpty());
    }

    @Test
    void validateRejectsInvalidInteractionContract() {
        AgentReleaseValidationResult result = service.validate(baseDefinition(GraphSpec.builder()
                .entry("llm")
                .finishNode("interaction")
                .node(GraphSpec.Node.builder().id("llm").type("LLM").build())
                .node(GraphSpec.Node.builder()
                        .id("interaction")
                        .type("INTERACTION")
                        .config(Map.of(
                                "interactionType", "COLLECT_INPUT",
                                "fields", List.of(
                                        Map.of("key", "bad-name", "type", "string"),
                                        Map.of("key", "bad-name", "type", "date"))))
                        .build())
                .edge(GraphSpec.Edge.builder().from("START").to("llm").condition("always").build())
                .edge(GraphSpec.Edge.builder().from("llm").to("interaction").condition("always").build())
                .edge(GraphSpec.Edge.builder().from("interaction").to("END").condition("always").build())
                .build()));

        assertFalse(result.valid());
        List<String> codes = result.errors().stream().map(AgentReleaseValidationResult.Item::code).toList();
        assertTrue(codes.contains("GRAPH_INTERACTION_FIELD_NAME_INVALID"));
        assertTrue(codes.contains("GRAPH_INTERACTION_FIELD_DUPLICATE"));
        assertTrue(codes.contains("GRAPH_INTERACTION_FIELD_TYPE_INVALID"));
    }

    @Test
    void validateRejectsUnsupportedInteractionType() {
        AgentReleaseValidationResult result = service.validate(baseDefinition(GraphSpec.builder()
                .entry("llm")
                .finishNode("interaction")
                .node(GraphSpec.Node.builder().id("llm").type("LLM").build())
                .node(GraphSpec.Node.builder()
                        .id("interaction")
                        .type("INTERACTION")
                        .config(Map.of("interactionType", "FREE_TEXT_WIZARD"))
                        .build())
                .edge(GraphSpec.Edge.builder().from("START").to("llm").condition("always").build())
                .edge(GraphSpec.Edge.builder().from("llm").to("interaction").condition("always").build())
                .edge(GraphSpec.Edge.builder().from("interaction").to("END").condition("always").build())
                .build()));

        assertFalse(result.valid());
        List<String> codes = result.errors().stream().map(AgentReleaseValidationResult.Item::code).toList();
        assertTrue(codes.contains("GRAPH_INTERACTION_TYPE_INVALID"));
    }

    @Test
    void validateAcceptsPresentOutputWithRegisteredCustomRenderer() {
        AgentReleaseValidationResult result = service.validate(baseDefinition(GraphSpec.builder()
                .entry("llm")
                .finishNode("display")
                .node(GraphSpec.Node.builder().id("llm").type("LLM").build())
                .node(GraphSpec.Node.builder()
                        .id("display")
                        .type("INTERACTION")
                        .config(Map.of(
                                "interactionType", "PRESENT_OUTPUT",
                                "component", "CUSTOM",
                                "renderSchema", Map.of("rendererKey", "enterprise.team.detail")))
                        .build())
                .edge(GraphSpec.Edge.builder().from("START").to("llm").condition("always").build())
                .edge(GraphSpec.Edge.builder().from("llm").to("display").condition("always").build())
                .edge(GraphSpec.Edge.builder().from("display").to("END").condition("always").build())
                .build()));

        assertTrue(result.valid(), () -> result.errors().toString());
    }

    @Test
    void validateRejectsCustomPresentOutputWithoutSafeRendererKey() {
        AgentReleaseValidationResult result = service.validate(baseDefinition(GraphSpec.builder()
                .entry("llm")
                .finishNode("display_missing")
                .node(GraphSpec.Node.builder().id("llm").type("LLM").build())
                .node(GraphSpec.Node.builder()
                        .id("display")
                        .type("INTERACTION")
                        .config(Map.of(
                                "interactionType", "PRESENT_OUTPUT",
                                "component", "CUSTOM",
                                "renderSchema", Map.of("rendererKey", "<script>alert(1)</script>")))
                        .build())
                .node(GraphSpec.Node.builder()
                        .id("display_missing")
                        .type("INTERACTION")
                        .config(Map.of(
                                "interactionType", "PRESENT_OUTPUT",
                                "component", "CUSTOM",
                                "renderSchema", Map.of()))
                        .build())
                .edge(GraphSpec.Edge.builder().from("START").to("llm").condition("always").build())
                .edge(GraphSpec.Edge.builder().from("llm").to("display").condition("always").build())
                .edge(GraphSpec.Edge.builder().from("display").to("display_missing").condition("always").build())
                .edge(GraphSpec.Edge.builder().from("display_missing").to("END").condition("always").build())
                .build()));

        assertFalse(result.valid());
        List<String> codes = result.errors().stream().map(AgentReleaseValidationResult.Item::code).toList();
        assertTrue(codes.contains("GRAPH_INTERACTION_CUSTOM_RENDERER_INVALID"));
        assertEquals(2, codes.stream().filter("GRAPH_INTERACTION_CUSTOM_RENDERER_INVALID"::equals).count());
    }

    @Test
    void graphNodeCatalogNormalizesCanvasAndSdkAliases() {
        assertEquals("INTENT_CLASSIFIER", AgentGraphNodeType.normalize("classifier"));
        assertEquals("HUMAN_APPROVAL", AgentGraphNodeType.normalize("human_approval"));
        assertEquals("KNOWLEDGE_WRITE", AgentGraphNodeType.normalize("knowledgeWrite"));
        assertTrue(AgentGraphNodeType.find("mcp").orElseThrow().isFlow());
        assertEquals("PAGE_ACTION", AgentGraphNodeType.normalize("pageAction"));
        assertTrue(AgentGraphNodeType.catalog().stream()
                .anyMatch(item -> "DOCUMENT_EXTRACT".equals(item.type())
                        && "documentExtract".equals(item.canvasKind())));
    }

    @Test
    void validateRejectsInvalidPseudoNodeDirection() {
        AgentReleaseValidationResult result = service.validate(baseDefinition(GraphSpec.builder()
                .entry("llm")
                .finishNode("llm")
                .node(GraphSpec.Node.builder().id("llm").type("LLM").build())
                .edge(GraphSpec.Edge.builder().from("END").to("llm").condition("always").build())
                .edge(GraphSpec.Edge.builder().from("llm").to("START").condition("always").build())
                .build()));

        assertFalse(result.valid());
        List<String> codes = result.errors().stream().map(AgentReleaseValidationResult.Item::code).toList();
        assertTrue(codes.contains("GRAPH_EDGE_FROM_END"));
        assertTrue(codes.contains("GRAPH_EDGE_TO_START"));
    }

    @Test
    void validateReportsNullGraphItemsWithoutCrashing() {
        java.util.ArrayList<GraphSpec.Node> nodes = new java.util.ArrayList<>();
        nodes.add(null);
        nodes.add(GraphSpec.Node.builder().id("llm").type("LLM").build());
        java.util.ArrayList<GraphSpec.Edge> edges = new java.util.ArrayList<>();
        edges.add(null);
        edges.add(GraphSpec.Edge.builder().from("START").to("llm").condition("always").build());
        edges.add(GraphSpec.Edge.builder().from("llm").to("END").condition("always").build());

        AgentReleaseValidationResult result = service.validate(baseDefinition(GraphSpec.builder()
                .entry("llm")
                .finishNode("llm")
                .nodes(nodes)
                .edges(edges)
                .build()));

        assertFalse(result.valid());
        List<String> codes = result.errors().stream().map(AgentReleaseValidationResult.Item::code).toList();
        assertTrue(codes.contains("GRAPH_NODE_EMPTY_ITEM"));
        assertTrue(codes.contains("GRAPH_EDGE_EMPTY"));
    }

    @Test
    void validateRejectsUnreachableAndMissingTool() {
        when(toolDefinitionService.findByName("missing_tool")).thenReturn(Optional.empty());
        AgentReleaseValidationResult result = service.validate(baseDefinition(GraphSpec.builder()
                .entry("llm")
                .finishNode("end")
                .node(GraphSpec.Node.builder().id("llm").type("LLM").build())
                .node(GraphSpec.Node.builder()
                        .id("tool")
                        .type("TOOL")
                        .ref(GraphSpec.CapabilityRef.builder().name("missing_tool").build())
                        .build())
                .node(GraphSpec.Node.builder().id("end").type("LLM").build())
                .edge(GraphSpec.Edge.builder().from("llm").to("end").condition("sometimes").build())
                .build()));

        assertFalse(result.valid());
        List<String> codes = result.errors().stream().map(AgentReleaseValidationResult.Item::code).toList();
        assertTrue(codes.contains("GRAPH_REF_NOT_FOUND"));
        assertTrue(codes.contains("GRAPH_EDGE_CONDITION_UNSUPPORTED"));
        assertTrue(codes.contains("GRAPH_UNREACHABLE_NODE"));
    }

    @Test
    void validateRejectsUnconfiguredPlaceholderNodes() {
        AgentReleaseValidationResult result = service.validate(baseDefinition(GraphSpec.builder()
                .entry("llm")
                .finishNode("reply")
                .node(GraphSpec.Node.builder().id("llm").type("LLM").build())
                .node(GraphSpec.Node.builder()
                        .id("placeholder_query_order")
                        .type("TOOL")
                        .name("待配置：查询订单")
                        .config(Map.of(
                                "needsConfiguration", true,
                                "placeholderReason", "没有匹配到已注册能力"))
                        .build())
                .node(GraphSpec.Node.builder()
                        .id("reply")
                        .type("ANSWER")
                        .config(Map.of("template", "{{ lastOutput }}"))
                        .build())
                .edge(GraphSpec.Edge.builder().from("START").to("llm").condition("always").build())
                .edge(GraphSpec.Edge.builder().from("llm").to("placeholder_query_order").condition("always").build())
                .edge(GraphSpec.Edge.builder().from("placeholder_query_order").to("reply").condition("success").build())
                .edge(GraphSpec.Edge.builder().from("reply").to("END").condition("always").build())
                .build()));

        assertFalse(result.valid());
        List<String> codes = result.errors().stream().map(AgentReleaseValidationResult.Item::code).toList();
        assertTrue(codes.contains("GRAPH_PLACEHOLDER_NODE_UNCONFIGURED"));
    }

    @Test
    void validateRejectsInvalidUserInputFields() {
        AgentReleaseValidationResult result = service.validate(baseDefinition(GraphSpec.builder()
                .entry("user_input")
                .finishNode("answer")
                .node(GraphSpec.Node.builder()
                        .id("user_input")
                        .type("USER_INPUT")
                        .config(Map.of("fields", List.of(
                                Map.of("name", "question", "type", "string", "required", true),
                                Map.of("name", "question", "type", "file", "required", false))))
                        .build())
                .node(GraphSpec.Node.builder()
                        .id("answer")
                        .type("ANSWER")
                        .config(Map.of("template", "{{ params.question }}"))
                        .build())
                .edge(GraphSpec.Edge.builder().from("START").to("user_input").condition("always").build())
                .edge(GraphSpec.Edge.builder().from("user_input").to("answer").condition("always").build())
                .edge(GraphSpec.Edge.builder().from("answer").to("END").condition("always").build())
                .build()));

        assertFalse(result.valid());
        List<String> codes = result.errors().stream().map(AgentReleaseValidationResult.Item::code).toList();
        assertTrue(codes.contains("GRAPH_USER_INPUT_FIELD_DUPLICATE"));
    }

    @Test
    void validateRejectsRequiredInputWithoutBinding() {
        AgentReleaseValidationResult result = service.validate(baseDefinition(GraphSpec.builder()
                .entry("user_input")
                .finishNode("reply")
                .node(GraphSpec.Node.builder()
                        .id("user_input")
                        .type("USER_INPUT")
                        .config(Map.of(
                                "outputAlias", "params",
                                "fields", List.of(Map.of("name", "question", "type", "string", "required", true))))
                        .build())
                .node(GraphSpec.Node.builder()
                        .id("call_tool")
                        .type("TOOL")
                        .inputs(List.of(GraphSpec.Port.builder()
                                .id("query")
                                .name("query")
                                .type("string")
                                .required(true)
                                .build()))
                        .ref(GraphSpec.CapabilityRef.builder().name("query_order").build())
                        .config(Map.of("outputAlias", "tool_result"))
                        .build())
                .node(GraphSpec.Node.builder()
                        .id("reply")
                        .type("ANSWER")
                        .config(Map.of("template", "{{ tool_result }}"))
                        .build())
                .edge(GraphSpec.Edge.builder().from("START").to("user_input").condition("always").build())
                .edge(GraphSpec.Edge.builder().from("user_input").to("call_tool").condition("always").build())
                .edge(GraphSpec.Edge.builder().from("call_tool").to("reply").condition("success").build())
                .edge(GraphSpec.Edge.builder().from("reply").to("END").condition("always").build())
                .build()));

        List<String> codes = result.errors().stream().map(AgentReleaseValidationResult.Item::code).toList();
        assertTrue(codes.contains("GRAPH_REQUIRED_INPUT_UNBOUND"));
    }

    private AgentDefinition baseDefinition(GraphSpec graphSpec) {
        return AgentDefinition.builder()
                .id("agent-1")
                .keySlug("order-agent")
                .name("Order Agent")
                .enabled(true)
                .modelInstanceId("model-1")
                .runtimeType("LANGGRAPH4J")
                .runtimePlacement("CENTRAL")
                .graphSpec(graphSpec)
                .build();
    }
}
