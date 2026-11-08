package com.enterprise.ai.runtime.execution.kernel;

import com.enterprise.ai.agent.graph.GraphSpec;
import com.enterprise.ai.runtime.execution.RuntimeGraphSpecExecutionResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuntimeParallelMapMergeTest {
    @Test
    void mergesNewAndNullNestedMapsInEachNamespaceInBranchOrder() throws Exception {
        String json = """
                {"schemaVersion":2,"entryNodeId":"fork","exitNodeIds":["end"],
                 "nodes":[{"id":"fork","type":"PARALLEL","config":{}},
                          {"id":"a","type":"TEMPLATE"},{"id":"b","type":"TEMPLATE"},
                          {"id":"end","type":"ANSWER"}],
                 "edges":[{"from":"fork","to":"a","priority":1},{"from":"fork","to":"b","priority":2},
                          {"from":"a","to":"end"},{"from":"b","to":"end"}]}
                """;
        GraphSpec graph = new ObjectMapper().readValue(json, GraphSpec.class);
        var handler = new RuntimeParallelNodeHandler((g, nodes, members, entry, join, context, sink, cancellation) -> {
            String value = "a".equals(entry) ? "first" : "second";
            String field = "a".equals(entry) ? "left" : "right";
            for (String namespace : List.of("var", "params", "nodeOutput")) {
                Map<String, Object> variables = new LinkedHashMap<>();
                variables.put("nested", Map.of(field, value, "conflict", value));
                context.put(namespace, variables);
            }
            return new RuntimeGraphSpecExecutionResult(true, "OK", value, entry, "TEMPLATE", List.of(), Map.of());
        }, new RuntimeConcurrentNodeRunner(List.of()));
        Map<String, Object> context = new LinkedHashMap<>();
        Map<String, Object> initialVars = new LinkedHashMap<>();
        initialVars.put("nested", null);
        context.put("var", initialVars);
        context.put("params", Map.of());

        var result = handler.executeParallel(graph.getNodes().get(0),
                new RuntimeNodeExecutionContext(graph, context, null, null));
        assertTrue(result.success(), result::answer);
        for (String namespace : List.of("var", "params", "nodeOutput")) {
            assertEquals(Map.of("nested", Map.of("left", "first", "right", "second", "conflict", "second")),
                    context.get(namespace), namespace);
        }
    }
}
