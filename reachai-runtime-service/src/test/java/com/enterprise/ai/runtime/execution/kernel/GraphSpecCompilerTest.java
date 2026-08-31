package com.enterprise.ai.runtime.execution.kernel;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class GraphSpecCompilerTest {

    private final GraphSpecCompiler compiler = new GraphSpecCompiler(new ObjectMapper());

    @Test
    void compilesCanonicalGraphAndSupportsValidatedEntryOverride() {
        ExecutableGraph graph = compiler.compile("""
                {"schemaVersion":2,"entryNodeId":"start","exitNodeIds":["finish"],
                 "nodes":[{"id":"start","type":"ANSWER"},{"id":"finish","type":"ANSWER"}],
                 "edges":[{"from":"start","to":"finish"}]}
                """, "finish");

        assertEquals("start", graph.declaredEntryNodeId());
        assertEquals("finish", graph.executionEntryNodeId());
        assertEquals(2, graph.nodesById().size());
    }

    @Test
    void rejectsBlankNodeIdBeforeExecution() {
        GraphSpecCompilationException failure = assertThrows(GraphSpecCompilationException.class,
                () -> compiler.compile("""
                        {"schemaVersion":2,"entryNodeId":"start","exitNodeIds":["start"],
                         "nodes":[{"id":" ","type":"ANSWER"}],"edges":[]}
                        """, null));

        assertEquals("RUNTIME_GRAPH_NODE_ID_INVALID", failure.code());
    }

    @Test
    void rejectsDuplicateNodeIdsAfterCanonicalization() {
        GraphSpecCompilationException failure = assertThrows(GraphSpecCompilationException.class,
                () -> compiler.compile("""
                        {"schemaVersion":2,"entryNodeId":"start","exitNodeIds":["start"],
                         "nodes":[{"id":"start","type":"ANSWER"},{"id":" start ","type":"ANSWER"}],
                         "edges":[]}
                        """, null));

        assertEquals("RUNTIME_GRAPH_NODE_ID_DUPLICATE", failure.code());
        assertEquals("start", failure.nodeId());
    }
}
