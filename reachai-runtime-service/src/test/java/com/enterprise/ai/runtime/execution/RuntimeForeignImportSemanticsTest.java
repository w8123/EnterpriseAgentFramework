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

class RuntimeForeignImportSemanticsTest {
    private final RuntimeGraphSpecExecutor executor = new RuntimeGraphSpecExecutor(new ObjectMapper(),
            mock(RuntimeModelServiceClient.class), mock(RuntimeCapabilityCatalogClient.class),
            mock(RuntimeControlCatalogClient.class));

    @Test
    void importedInputsPreserveNamedParametersWithAndWithoutSources() {
        String graph = """
                {"entryNodeId":"input","exitNodeIds":["answer"],"nodes":[
                  {"id":"input","type":"USER_INPUT","config":{"outputAlias":"params","fields":[
                    {"name":"name","type":"string"%s},
                    {"name":"qty","type":"integer"%s},
                    {"name":"active","type":"boolean"%s}]}},
                  {"id":"answer","type":"ANSWER","config":{"template":"{{ params.name }}|{{ params.qty }}|{{ params.active }}"}}
                ],"edges":[{"from":"input","to":"answer","condition":"always"}]}
                """;
        Map<String, Object> request = Map.of("message", "generic message", "input", "generic message",
                "params", Map.of("name", "Alice", "qty", 0, "active", false));
        for (String source : new String[]{"legacy", "explicit"}) {
            boolean explicit = source.equals("explicit");
            var result = executor.execute(graph.formatted(explicit ? ",\"source\":\"input.name\"" : "",
                    explicit ? ",\"source\":\"input.qty\"" : "",
                    explicit ? ",\"source\":\"input.active\"" : ""), request);
            assertTrue(result.success(), result::answer);
            assertEquals("Alice|0|false", result.answer());
        }
    }

    @Test
    void importedDifyFailBranchRoutesToErrorEdge() {
        var result = executor.execute("""
                {"entryNodeId":"code","exitNodeIds":["success","error"],"nodes":[
                  {"id":"code","type":"CODE","config":{"language":"javascript",
                    "code":"function main(inputs) { throw new Error('fail'); }"}},
                  {"id":"success","type":"ANSWER","config":{"template":"success"}},
                  {"id":"error","type":"ANSWER","config":{"template":"error branch"}}
                ],"edges":[{"from":"code","to":"success","condition":"always"},
                  {"from":"code","to":"error","condition":"error"}]}
                """, Map.of());
        assertTrue(result.success(), result::answer);
        assertEquals("error branch", result.answer());
    }

    @Test
    void importedDifyDefaultOutputContinuesWithTypedValues() {
        var result = executor.execute("""
                {"entryNodeId":"code","exitNodeIds":["answer"],"nodes":[
                  {"id":"code","type":"CODE",
                    "errorPolicy":{"strategy":"CONTINUE","defaultOutput":{"text":"fallback","count":2}},
                    "config":{"language":"javascript","outputAlias":"calc",
                      "code":"function main(inputs) { throw new Error('fail'); }"}},
                  {"id":"answer","type":"ANSWER","config":{"template":"{{ var.calc.text }}|{{ var.calc.count }}"}}
                ],"edges":[{"from":"code","to":"answer","condition":"always"}]}
                """, Map.of());
        assertTrue(result.success(), result::answer);
        assertEquals("fallback|2", result.answer());
    }
}
