package com.enterprise.ai.runtime.execution;

import com.enterprise.ai.runtime.client.capability.RuntimeCapabilityCatalogClient;
import com.enterprise.ai.runtime.client.control.RuntimeControlCatalogClient;
import com.enterprise.ai.runtime.client.model.RuntimeModelServiceClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class RuntimeParallelMergeRegressionTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void branchesMergeOnlyChangesFromTheirSharedStartingState() {
        String graph = """
                {"schemaVersion":2,"entryNodeId":"fork","exitNodeIds":["answer"],
                 "nodes":[
                   {"id":"fork","type":"PARALLEL","config":{}},
                   {"id":"a","type":"VARIABLE_ASSIGN","config":{"assignments":{"var.data.left":"new-left","var.shared":"first"}}},
                   {"id":"b","type":"VARIABLE_ASSIGN","config":{"assignments":{"var.data.right":"new-right","var.shared":"second"}}},
                   {"id":"answer","type":"ANSWER","config":{"template":"{{ var.data.left }}|{{ var.data.right }}|{{ var.shared }}"}}],
                 "edges":[{"from":"fork","to":"a","priority":1},{"from":"fork","to":"b","priority":2},
                          {"from":"a","to":"answer"},{"from":"b","to":"answer"}]}
                """;
        var executor = new RuntimeGraphSpecExecutor(new ObjectMapper(), mock(RuntimeModelServiceClient.class),
                mock(RuntimeCapabilityCatalogClient.class), mock(RuntimeControlCatalogClient.class));
        var result = executor.execute(graph, Map.of("var", Map.of("data", Map.of("left", "old-left", "right", "old-right"),
                "shared", "old")));
        assertTrue(result.success(), result::answer);
        assertEquals("new-left|new-right|second", result.answer());
    }

    @Test
    void branchesCanCreateDifferentFieldsUnderTheSameNewObject() {
        String graph = """
                {"schemaVersion":2,"entryNodeId":"fork","exitNodeIds":["answer"],
                 "nodes":[
                   {"id":"fork","type":"PARALLEL","config":{}},
                   {"id":"a","type":"VARIABLE_ASSIGN","config":{"assignments":{"var.data.left":"new-left"}}},
                   {"id":"b","type":"VARIABLE_ASSIGN","config":{"assignments":{"var.data.right":"new-right"}}},
                   {"id":"answer","type":"ANSWER","config":{"template":"{{ var.data.left }}|{{ var.data.right }}"}}],
                 "edges":[{"from":"fork","to":"a","priority":1},{"from":"fork","to":"b","priority":2},
                          {"from":"a","to":"answer"},{"from":"b","to":"answer"}]}
                """;
        var executor = new RuntimeGraphSpecExecutor(mapper, mock(RuntimeModelServiceClient.class),
                mock(RuntimeCapabilityCatalogClient.class), mock(RuntimeControlCatalogClient.class));

        var missing = executor.execute(graph, Map.of());
        assertTrue(missing.success(), missing::answer);
        assertEquals("new-left|new-right", missing.answer());

        Map<String, Object> variables = new java.util.LinkedHashMap<>();
        variables.put("data", null);
        var nullValue = executor.execute(graph, Map.of("var", variables));
        assertTrue(nullValue.success(), nullValue::answer);
        assertEquals("new-left|new-right", nullValue.answer());
    }

    @Test
    void parallelBranchesPreserveBothFirstNodeOutputs() throws Exception {
        String graph = """
                {"schemaVersion":2,"entryNodeId":"fork","exitNodeIds":["answer"],
                 "nodes":[{"id":"fork","type":"PARALLEL","config":{}},
                          {"id":"a","type":"TEMPLATE","config":{"template":"first"}},
                          {"id":"b","type":"TEMPLATE","config":{"template":"second"}},
                          {"id":"answer","type":"ANSWER","config":{"template":"{{ nodeOutput }}"}}],
                 "edges":[{"from":"fork","to":"a","priority":1},{"from":"fork","to":"b","priority":2},
                          {"from":"a","to":"answer"},{"from":"b","to":"answer"}]}
                """;
        var executor = new RuntimeGraphSpecExecutor(mapper, mock(RuntimeModelServiceClient.class),
                mock(RuntimeCapabilityCatalogClient.class), mock(RuntimeControlCatalogClient.class));
        var result = executor.execute(graph, Map.of());
        assertTrue(result.success(), result::answer);
        var outputs = mapper.readTree(result.answer());
        assertEquals("first", outputs.path("a").asText());
        assertEquals("second", outputs.path("b").asText());
    }
}
