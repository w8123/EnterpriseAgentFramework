package com.enterprise.ai.runtime.execution.interaction;

import com.enterprise.ai.agent.graph.GraphSpec;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class WorkflowInteractionUiRequestFactoryTest {

    @Test
    void resolvesDollarPrefixedBusinessAliasPathAndPromotesRenderSchema() {
        List<Map<String, Object>> records = List.of(
                Map.of("id", "t1", "name", "Team One"),
                Map.of("id", "t2", "name", "Team Two"));
        GraphSpec.Node node = GraphSpec.Node.builder()
                .id("show")
                .type("INTERACTION")
                .name("Results")
                .config(Map.of(
                        "interactionType", "PRESENT_OUTPUT",
                        "component", "LIST_CARD",
                        "dataExpression", "$.team_result.data.records",
                        "renderSchema", Map.of(
                                "titleField", "name",
                                "initialVisibleCount", 5)))
                .build();
        Map<String, Object> context = Map.of(
                "var", Map.of(
                        "team_result", Map.of(
                                "data", Map.of("records", records))),
                "lastOutput", Map.of("unexpected", true));

        WorkflowInteractionUiRequest request = WorkflowInteractionUiRequestFactory.build(
                node, context, "ix-1", WorkflowInteractionType.PRESENT_OUTPUT);

        assertEquals("list_card", request.component());
        assertEquals(records, request.data());
        assertEquals("name", request.schema().get("titleField"));
        assertEquals(5, request.schema().get("initialVisibleCount"));
        assertEquals(false, request.behavior().get("blocking"));
    }

    @Test
    void resolvesNestedLastOutputPath() {
        List<Map<String, Object>> records = List.of(Map.of("id", 1));
        GraphSpec.Node node = GraphSpec.Node.builder()
                .id("show")
                .type("INTERACTION")
                .config(Map.of(
                        "interactionType", "PRESENT_OUTPUT",
                        "component", "list_card",
                        "dataExpression", "lastOutput.records"))
                .build();

        WorkflowInteractionUiRequest request = WorkflowInteractionUiRequestFactory.build(
                node,
                Map.of("lastOutput", Map.of("records", records, "total", 1)),
                "ix-2",
                WorkflowInteractionType.PRESENT_OUTPUT);

        assertEquals(records, request.data());
    }
}
