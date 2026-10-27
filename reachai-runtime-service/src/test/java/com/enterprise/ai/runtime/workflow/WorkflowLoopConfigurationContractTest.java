package com.enterprise.ai.runtime.workflow;

import com.enterprise.ai.agent.graph.GraphSpec;
import com.enterprise.ai.runtime.client.capability.RuntimeCapabilityCatalogClient;
import com.enterprise.ai.runtime.client.control.RuntimeControlCatalogClient;
import com.enterprise.ai.runtime.client.model.RuntimeModelServiceClient;
import com.enterprise.ai.runtime.execution.RuntimeGraphSpecExecutor;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class WorkflowLoopConfigurationContractTest {

    private final ObjectMapper json = new ObjectMapper();
    private final RuntimeControlCatalogClient catalog = mock(RuntimeControlCatalogClient.class);
    private final RuntimeWorkflowReleaseValidationService validator =
            new RuntimeWorkflowReleaseValidationService(catalog, json);
    private final RuntimeGraphSpecExecutor executor = new RuntimeGraphSpecExecutor(
            json, mock(RuntimeModelServiceClient.class), mock(RuntimeCapabilityCatalogClient.class), catalog);

    @Test
    void publicationAndExecutionUseTheSameNestedConfigurationPrecedence() throws Exception {
        Map<String, Object> config = Map.of(
                "collection", "var.missing",
                "bodyEntry", "missing",
                "maxIterations", 1,
                "loopConfig", Map.of(
                        "itemExpression", "var.items",
                        "collection", "var.items",
                        "loopKey", "results",
                        "bodyEntry", "body",
                        "maxIterations", 2));

        GraphSpec graph = graph(config);
        assertTrue(validator.validateProposed(workflow(), graph).valid());
        var result = executor.execute(json.writeValueAsString(graph),
                Map.of("var", Map.of("items", List.of("first", "second"))));

        assertTrue(result.success(), result::answer);
        assertEquals(List.of("first", "second"), result.metadata().get("structuredOutput"));
    }

    @Test
    void publicationAndExecutionRejectTheSameInvalidLimit() throws Exception {
        GraphSpec graph = graph(Map.of("loopConfig", Map.of(
                "collection", "var.items", "bodyEntry", "body", "maxIterations", "invalid")));

        assertTrue(validator.validateProposed(workflow(), graph).errors().stream()
                .anyMatch(error -> "GRAPH_LOOP_MAX_ITERATIONS_INVALID".equals(error.code())));
        var result = executor.execute(json.writeValueAsString(graph),
                Map.of("var", Map.of("items", List.of("first"))));
        assertEquals("RUNTIME_GRAPH_LOOP_MAX_ITERATIONS_INVALID", result.code());
    }

    @ParameterizedTest
    @MethodSource("invalidNumericLimits")
    void publicationAndExecutionRejectOutOfRangeOrFractionalNumbers(Number limit) throws Exception {
        GraphSpec graph = graph(Map.of("loopConfig", Map.of(
                "collection", "var.items", "bodyEntry", "body", "maxIterations", limit)));

        assertTrue(validator.validateProposed(workflow(), graph).errors().stream()
                .anyMatch(error -> "GRAPH_LOOP_MAX_ITERATIONS_INVALID".equals(error.code())));
        var result = executor.execute(json.writeValueAsString(graph),
                Map.of("var", Map.of("items", List.of("first"))));
        assertEquals("RUNTIME_GRAPH_LOOP_MAX_ITERATIONS_INVALID", result.code());
    }

    static Stream<Number> invalidNumericLimits() {
        return Stream.of(4294967297L, new BigInteger("18446744073709551617"), new BigDecimal("1.9"));
    }

    @ParameterizedTest
    @MethodSource("conflictingAliases")
    void publicationAndExecutionRejectConflictingAliases(String item, String index, String output) throws Exception {
        GraphSpec graph = graph(Map.of("collection", "var.items", "bodyEntry", "body",
                "itemAlias", item, "indexAlias", index, "outputAlias", output));
        assertTrue(validator.validateProposed(workflow(), graph).errors().stream()
                .anyMatch(error -> "GRAPH_LOOP_ALIAS_CONFLICT".equals(error.code())));
        var result = executor.execute(json.writeValueAsString(graph),
                Map.of("var", Map.of("items", List.of("first"))));
        assertEquals("RUNTIME_GRAPH_LOOP_ALIAS_CONFLICT", result.code());
    }

    static Stream<Arguments> conflictingAliases() {
        return Stream.of(Arguments.of("same", "same", "results"),
                Arguments.of("same", "index", "same"), Arguments.of("item", "same", "same"));
    }

    private GraphSpec graph(Map<String, Object> loopConfig) {
        return GraphSpec.builder()
                .entryNodeId("loop")
                .exitNodeIds(List.of("loop"))
                .node(GraphSpec.Node.builder().id("loop").type("LOOP").config(loopConfig).build())
                .node(GraphSpec.Node.builder().id("body").type("TEMPLATE")
                        .config(Map.of("template", "{{ var.item }}")).build())
                .build();
    }

    private RuntimeWorkflowDefinitionEntity workflow() {
        RuntimeWorkflowDefinitionEntity workflow = new RuntimeWorkflowDefinitionEntity();
        workflow.setId("workflow-loop-contract");
        workflow.setWorkflowKind("GENERAL");
        return workflow;
    }
}
