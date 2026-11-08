package com.enterprise.ai.runtime.execution.kernel;

import com.enterprise.ai.agent.graph.GraphSpec;
import com.enterprise.ai.runtime.execution.RuntimeGraphSpecExecutionResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class RuntimeParallelUnknownWriteTest {
    @Test
    void unknownWriteStopsParallelEvenWhenConfiguredToContinue() throws Exception {
        String json = """
                {"schemaVersion":2,"entryNodeId":"fork","exitNodeIds":["end"],
                 "nodes":[{"id":"fork","type":"PARALLEL","config":{"failureMode":"CONTINUE"}},
                          {"id":"a","type":"TEMPLATE"},{"id":"b","type":"TEMPLATE"},
                          {"id":"end","type":"ANSWER"}],
                 "edges":[{"from":"fork","to":"a"},{"from":"fork","to":"b"},
                          {"from":"a","to":"end"},{"from":"b","to":"end"}]}
                """;
        GraphSpec graph = new ObjectMapper().readValue(json, GraphSpec.class);
        var handler = new RuntimeParallelNodeHandler((g, nodes, members, entry, join, context, sink, cancellation) ->
                "a".equals(entry)
                        ? new RuntimeGraphSpecExecutionResult(false, "WRITE_UNKNOWN", "unknown", entry, "TOOL",
                            List.of(), Map.of("nonRecoverableWrite", true, "dispatchStage", "UNKNOWN"))
                        : new RuntimeGraphSpecExecutionResult(true, "OK", "ok", entry, "TEMPLATE", List.of(), Map.of()),
                new RuntimeConcurrentNodeRunner(List.of()));
        var result = handler.executeParallel(graph.getNodes().get(0),
                new RuntimeNodeExecutionContext(graph, new LinkedHashMap<>(), null, null));
        assertFalse(result.success());
        assertEquals("WRITE_UNKNOWN", result.code());
        assertEquals(true, result.metadata().get("nonRecoverableWrite"));
        assertEquals("UNKNOWN", result.metadata().get("dispatchStage"));
    }
}
