package com.enterprise.ai.agent.agent;

import com.enterprise.ai.agent.graph.AgentGraphSpec;
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

        AgentReleaseValidationResult result = service.validate(baseDefinition(AgentGraphSpec.builder()
                .entry("llm")
                .finishNode("tool")
                .node(AgentGraphSpec.Node.builder().id("llm").type("LLM").build())
                .node(AgentGraphSpec.Node.builder()
                        .id("tool")
                        .type("TOOL")
                        .ref(AgentGraphSpec.CapabilityRef.builder().name("query_order").build())
                        .config(Map.of("inputMapping", Map.of("orderId", "$input")))
                        .build())
                .edge(AgentGraphSpec.Edge.builder().from("llm").to("tool").condition("success").build())
                .build()));

        assertTrue(result.valid(), () -> result.errors().toString());
        assertTrue(result.errors().isEmpty());
    }

    @Test
    void validateAcceptsSdkGraphWithStartAndEndPseudoNodes() {
        AgentReleaseValidationResult result = service.validate(baseDefinition(AgentGraphSpec.builder()
                .entry("llm")
                .finishNode("llm")
                .node(AgentGraphSpec.Node.builder()
                        .id("llm")
                        .type("LLM")
                        .config(Map.of(
                                "outputFormat", "json",
                                "outputSchema", List.of(Map.of(
                                        "name", "answer",
                                        "type", "string",
                                        "required", true))))
                        .build())
                .edge(AgentGraphSpec.Edge.builder().from("START").to("llm").condition("always").build())
                .edge(AgentGraphSpec.Edge.builder().from("llm").to("END").condition("always").build())
                .build()));

        assertTrue(result.valid(), () -> result.errors().toString());
        assertTrue(result.errors().isEmpty());
    }

    @Test
    void validateRejectsInvalidLlmOutputSchema() {
        AgentReleaseValidationResult result = service.validate(baseDefinition(AgentGraphSpec.builder()
                .entry("llm")
                .finishNode("llm")
                .node(AgentGraphSpec.Node.builder()
                        .id("llm")
                        .type("LLM")
                        .config(Map.of(
                                "outputFormat", "xml",
                                "outputSchema", List.of(Map.of("name", "answer", "type", "date"))))
                        .build())
                .edge(AgentGraphSpec.Edge.builder().from("START").to("llm").condition("always").build())
                .edge(AgentGraphSpec.Edge.builder().from("llm").to("END").condition("always").build())
                .build()));

        assertFalse(result.valid());
        List<String> codes = result.errors().stream().map(AgentReleaseValidationResult.Item::code).toList();
        assertTrue(codes.contains("GRAPH_LLM_OUTPUT_FORMAT_INVALID"));
        assertTrue(codes.contains("GRAPH_PARAMETER_FIELD_TYPE_INVALID"));
    }

    @Test
    void validateAcceptsFlowPrimitiveNodes() {
        AgentReleaseValidationResult result = service.validate(baseDefinition(AgentGraphSpec.builder()
                .entry("llm")
                .finishNode("reply")
                .node(AgentGraphSpec.Node.builder().id("llm").type("LLM").build())
                .node(AgentGraphSpec.Node.builder()
                        .id("vars")
                        .type("VARIABLE_ASSIGN")
                        .config(Map.of("assignments", Map.of("summary", "lastOutput")))
                        .build())
                .node(AgentGraphSpec.Node.builder()
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
                .node(AgentGraphSpec.Node.builder()
                        .id("reply")
                        .type("TEMPLATE")
                        .config(Map.of("template", "{{ summary }}"))
                        .build())
                .edge(AgentGraphSpec.Edge.builder().from("START").to("llm").condition("always").build())
                .edge(AgentGraphSpec.Edge.builder().from("llm").to("vars").condition("always").build())
                .edge(AgentGraphSpec.Edge.builder().from("vars").to("route").condition("always").build())
                .edge(AgentGraphSpec.Edge.builder().from("route").to("reply").condition("route:nonempty").build())
                .edge(AgentGraphSpec.Edge.builder().from("reply").to("END").condition("always").build())
                .build()));

        assertTrue(result.valid(), () -> result.errors().toString());
        assertTrue(result.errors().isEmpty());
    }

    @Test
    void validateAcceptsIntegrationAndRetrievalNodes() {
        AgentReleaseValidationResult result = service.validate(baseDefinition(AgentGraphSpec.builder()
                .entry("llm")
                .finishNode("knowledge")
                .node(AgentGraphSpec.Node.builder().id("llm").type("LLM").build())
                .node(AgentGraphSpec.Node.builder()
                        .id("extract")
                        .type("PARAMETER_EXTRACT")
                        .config(Map.of("extractMode", "expression", "fields", List.of(Map.of(
                                "name", "orderId",
                                "type", "string",
                                "required", true,
                                "source", "lastOutput.orderId"))))
                        .build())
                .node(AgentGraphSpec.Node.builder()
                        .id("api")
                        .type("HTTP_REQUEST")
                        .config(Map.of("method", "POST", "url", "https://example.com/api"))
                        .build())
                .node(AgentGraphSpec.Node.builder()
                        .id("knowledge")
                        .type("KNOWLEDGE_RETRIEVAL")
                        .config(Map.of("knowledgeBaseCodes", List.of("kb_order"), "query", "input"))
                        .build())
                .edge(AgentGraphSpec.Edge.builder().from("START").to("llm").condition("always").build())
                .edge(AgentGraphSpec.Edge.builder().from("llm").to("extract").condition("always").build())
                .edge(AgentGraphSpec.Edge.builder().from("extract").to("api").condition("always").build())
                .edge(AgentGraphSpec.Edge.builder().from("api").to("knowledge").condition("success").build())
                .edge(AgentGraphSpec.Edge.builder().from("knowledge").to("END").condition("always").build())
                .build()));

        assertTrue(result.valid(), () -> result.errors().toString());
        assertTrue(result.errors().isEmpty());
    }

    @Test
    void graphNodeCatalogNormalizesCanvasAndSdkAliases() {
        assertEquals("INTENT_CLASSIFIER", AgentGraphNodeType.normalize("classifier"));
        assertEquals("HUMAN_APPROVAL", AgentGraphNodeType.normalize("human_approval"));
        assertEquals("KNOWLEDGE_WRITE", AgentGraphNodeType.normalize("knowledgeWrite"));
        assertTrue(AgentGraphNodeType.find("mcp").orElseThrow().isFlow());
        assertTrue(AgentGraphNodeType.catalog().stream()
                .anyMatch(item -> "DOCUMENT_EXTRACT".equals(item.type())
                        && "documentExtract".equals(item.canvasKind())));
    }

    @Test
    void validateRejectsInvalidPseudoNodeDirection() {
        AgentReleaseValidationResult result = service.validate(baseDefinition(AgentGraphSpec.builder()
                .entry("llm")
                .finishNode("llm")
                .node(AgentGraphSpec.Node.builder().id("llm").type("LLM").build())
                .edge(AgentGraphSpec.Edge.builder().from("END").to("llm").condition("always").build())
                .edge(AgentGraphSpec.Edge.builder().from("llm").to("START").condition("always").build())
                .build()));

        assertFalse(result.valid());
        List<String> codes = result.errors().stream().map(AgentReleaseValidationResult.Item::code).toList();
        assertTrue(codes.contains("GRAPH_EDGE_FROM_END"));
        assertTrue(codes.contains("GRAPH_EDGE_TO_START"));
    }

    @Test
    void validateReportsNullGraphItemsWithoutCrashing() {
        java.util.ArrayList<AgentGraphSpec.Node> nodes = new java.util.ArrayList<>();
        nodes.add(null);
        nodes.add(AgentGraphSpec.Node.builder().id("llm").type("LLM").build());
        java.util.ArrayList<AgentGraphSpec.Edge> edges = new java.util.ArrayList<>();
        edges.add(null);
        edges.add(AgentGraphSpec.Edge.builder().from("START").to("llm").condition("always").build());
        edges.add(AgentGraphSpec.Edge.builder().from("llm").to("END").condition("always").build());

        AgentReleaseValidationResult result = service.validate(baseDefinition(AgentGraphSpec.builder()
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
        AgentReleaseValidationResult result = service.validate(baseDefinition(AgentGraphSpec.builder()
                .entry("llm")
                .finishNode("end")
                .node(AgentGraphSpec.Node.builder().id("llm").type("LLM").build())
                .node(AgentGraphSpec.Node.builder()
                        .id("tool")
                        .type("TOOL")
                        .ref(AgentGraphSpec.CapabilityRef.builder().name("missing_tool").build())
                        .build())
                .node(AgentGraphSpec.Node.builder().id("end").type("LLM").build())
                .edge(AgentGraphSpec.Edge.builder().from("llm").to("end").condition("sometimes").build())
                .build()));

        assertFalse(result.valid());
        List<String> codes = result.errors().stream().map(AgentReleaseValidationResult.Item::code).toList();
        assertTrue(codes.contains("GRAPH_REF_NOT_FOUND"));
        assertTrue(codes.contains("GRAPH_EDGE_CONDITION_UNSUPPORTED"));
        assertTrue(codes.contains("GRAPH_UNREACHABLE_NODE"));
    }

    private AgentDefinition baseDefinition(AgentGraphSpec graphSpec) {
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
