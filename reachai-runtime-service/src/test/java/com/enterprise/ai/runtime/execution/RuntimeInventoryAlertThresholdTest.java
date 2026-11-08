package com.enterprise.ai.runtime.execution;

import com.enterprise.ai.runtime.client.capability.RuntimeCapabilityCatalogClient;
import com.enterprise.ai.runtime.client.control.RuntimeControlCatalogClient;
import com.enterprise.ai.runtime.client.model.RuntimeModelServiceClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class RuntimeInventoryAlertThresholdTest {
    private static final String GRAPH = """
            {"schemaVersion":2,"entryNodeId":"input","exitNodeIds":["answer"],
             "nodes":[
               {"id":"input","type":"USER_INPUT","config":{"outputAlias":"params","fields":[
                 {"name":"threshold","type":"number","required":false,"source":"params.threshold","defaultValue":"10"}]}},
               {"id":"pick_low","type":"LIST_OPERATOR","config":{"outputAlias":"low_stock",
                 "source":"nodeOutput.query_stock","operations":[{"type":"filter","logic":"AND","conditions":[
                   {"left":"item.quantity","operator":"lt","right":"params.threshold"}]}]}},
               {"id":"answer","type":"ANSWER","config":{"template":"{{ var.low_stock.count }}"}}],
             "edges":[{"from":"input","to":"pick_low"},{"from":"pick_low","to":"answer"}]}
            """;

    private final RuntimeGraphSpecExecutor executor = new RuntimeGraphSpecExecutor(new ObjectMapper(),
            mock(RuntimeModelServiceClient.class), mock(RuntimeCapabilityCatalogClient.class),
            mock(RuntimeControlCatalogClient.class));

    @Test
    void inventoryFilterUsesCallerThresholdAndDefaultWhenOmitted() {
        var stock = List.of(Map.of("quantity", 3), Map.of("quantity", 8), Map.of("quantity", 12));
        Map<String, Object> outputs = Map.of("query_stock", stock);
        assertCount(Map.of("message", "check stock", "nodeOutput", outputs), "2");
        assertCount(Map.of("message", "check stock", "nodeOutput", outputs, "params", Map.of()), "2");
        assertCount(Map.of("message", "check stock", "nodeOutput", outputs, "params", Map.of("threshold", 5)), "1");
        assertCount(Map.of("message", "check stock", "nodeOutput", outputs, "params", Map.of("threshold", 15)), "3");
        assertCount(Map.of("message", "check stock", "nodeOutput", outputs, "params", Map.of("threshold", 0)), "0");
    }

    private void assertCount(Map<String, Object> input, String expected) {
        RuntimeGraphSpecExecutionResult result = executor.execute(GRAPH, input);
        assertTrue(result.success(), result::answer);
        assertEquals(expected, result.answer());
    }
}
