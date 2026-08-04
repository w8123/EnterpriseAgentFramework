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
        assertEquals(WorkflowInteractionPresentationPolicy.CARD_ONLY,
                request.presentation().get("mode"));
        assertEquals(request.presentation(), request.toMap().get("presentation"));
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

    @Test
    void honorsExplicitTextAndCardPresentationMode() {
        GraphSpec.Node node = GraphSpec.Node.builder()
                .id("show")
                .type("INTERACTION")
                .config(Map.of(
                        "interactionType", "PRESENT_OUTPUT",
                        "component", "detail",
                        "presentation", Map.of("mode", "text-and-card")))
                .build();

        WorkflowInteractionUiRequest request = WorkflowInteractionUiRequestFactory.build(
                node, Map.of("lastOutput", Map.of("name", "Team One")), "ix-3",
                WorkflowInteractionType.PRESENT_OUTPUT);

        assertEquals(WorkflowInteractionPresentationPolicy.TEXT_AND_CARD,
                request.presentation().get("mode"));
    }
}
