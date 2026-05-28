package com.enterprise.ai.agent.tools.dynamic;

import com.enterprise.ai.agent.agentscope.adapter.AiToolAgentAdapter;
import com.enterprise.ai.agent.skill.ToolExecutionContextHolder;
import com.enterprise.ai.agent.tool.log.ToolExecutionContext;
import com.enterprise.ai.agent.tools.definition.ToolDefinitionEntity;
import com.enterprise.ai.agent.tools.definition.ToolDefinitionParameter;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DynamicHttpAiToolLlmSchemaTest {

    @Test
    void llmParametersJsonSchema_nestedBodyChildrenBecomeProperties() throws Exception {
        String parametersJson = """
                [
                  {
                    "name": "body_json",
                    "type": "object",
                    "description": "请求体",
                    "required": true,
                    "location": "BODY",
                    "children": [
                      {
                        "name": "teamName",
                        "type": "string",
                        "description": "班组名称",
                        "required": false,
                        "location": null
                      },
                      {
                        "name": "page",
                        "type": "integer",
                        "description": "页码",
                        "required": false,
                        "location": null
                      }
                    ]
                  }
                ]
                """;

        ToolDefinitionEntity entity = new ToolDefinitionEntity();
        entity.setName("page");
        entity.setBaseUrl("http://127.0.0.1");
        entity.setEndpointPath("/teams/page");
        entity.setHttpMethod("POST");
        entity.setParametersJson(parametersJson);

        DynamicHttpAiTool tool = new DynamicHttpAiTool(entity, new ObjectMapper());
        Map<String, Object> root = tool.llmParametersJsonSchema();

        assertEquals("object", root.get("type"));
        @SuppressWarnings("unchecked")
        Map<String, Object> props = (Map<String, Object>) root.get("properties");
        assertTrue(props.containsKey("body_json"));

        @SuppressWarnings("unchecked")
        Map<String, Object> bodyProp = (Map<String, Object>) props.get("body_json");
        assertEquals("object", bodyProp.get("type"));
        assertEquals("请求体", bodyProp.get("description"));

        @SuppressWarnings("unchecked")
        Map<String, Object> nested = (Map<String, Object>) bodyProp.get("properties");
        assertEquals(2, nested.size());
        @SuppressWarnings("unchecked")
        Map<String, Object> teamName = (Map<String, Object>) nested.get("teamName");
        assertEquals("string", teamName.get("type"));
        assertEquals("班组名称", teamName.get("description"));

        @SuppressWarnings("unchecked")
        List<String> required = (List<String>) root.get("required");
        assertEquals(List.of("body_json"), required);

        @SuppressWarnings("unchecked")
        List<String> bodyRequired = (List<String>) bodyProp.get("required");
        assertTrue(bodyRequired == null || bodyRequired.isEmpty());
    }

    @Test
    void buildRootParametersSchema_arrayUsesFirstChildAsItems() {
        var body = new ToolDefinitionParameter(
                "ids",
                "array",
                "id 列表",
                true,
                "QUERY",
                List.of(new ToolDefinitionParameter(
                        "item",
                        "string",
                        "单个 id",
                        false,
                        null,
                        List.of()
                ))
        );
        Map<String, Object> root = DynamicHttpAiTool.buildRootParametersSchema(List.of(body));
        @SuppressWarnings("unchecked")
        Map<String, Object> idsProp = (Map<String, Object>) ((Map<String, Object>) root.get("properties")).get("ids");
        assertEquals("array", idsProp.get("type"));
        @SuppressWarnings("unchecked")
        Map<String, Object> items = (Map<String, Object>) idsProp.get("items");
        assertEquals("string", items.get("type"));
    }

    @Test
    void aiToolAgentAdapter_delegatesToLlmJsonSchemaProvider() {
        ToolDefinitionEntity entity = new ToolDefinitionEntity();
        entity.setName("t");
        entity.setBaseUrl("http://127.0.0.1");
        entity.setEndpointPath("/x");
        entity.setHttpMethod("POST");
        entity.setParametersJson("[{\"name\":\"body_json\",\"type\":\"object\",\"description\":\"b\",\"required\":true,\"location\":\"BODY\",\"children\":[{\"name\":\"k\",\"type\":\"string\",\"description\":\"key\",\"required\":true,\"location\":null}]}]");

        var adapter = new AiToolAgentAdapter(new DynamicHttpAiTool(entity, new ObjectMapper()));
        Map<String, Object> schema = adapter.getParameters();
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) ((Map<String, Object>) schema.get("properties")).get("body_json");
        @SuppressWarnings("unchecked")
        Map<String, Object> nestedProps = (Map<String, Object>) body.get("properties");
        assertTrue(nestedProps.containsKey("k"));
    }

    @Test
    void executeAddsRuntimeIdentityHeadersToBusinessToolRequest() throws Exception {
        Map<String, String> received = new ConcurrentHashMap<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/tool", exchange -> {
            received.put("project", exchange.getRequestHeaders().getFirst("X-EAF-Project-Code"));
            received.put("agent", exchange.getRequestHeaders().getFirst("X-EAF-Agent-Id"));
            received.put("trace", exchange.getRequestHeaders().getFirst("X-EAF-Trace-Id"));
            received.put("session", exchange.getRequestHeaders().getFirst("X-EAF-Session-Id"));
            received.put("user", exchange.getRequestHeaders().getFirst("X-EAF-User-Id"));
            received.put("globalUser", exchange.getRequestHeaders().getFirst("X-EAF-Global-User-Id"));
            received.put("roles", exchange.getRequestHeaders().getFirst("X-EAF-Roles"));
            byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            ToolDefinitionEntity entity = new ToolDefinitionEntity();
            entity.setName("businessTool");
            entity.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
            entity.setEndpointPath("/tool");
            entity.setHttpMethod("GET");
            entity.setParametersJson("[]");
            ToolExecutionContextHolder.set(ToolExecutionContext.builder()
                    .projectCode("bzsdk")
                    .agentId("team-agent")
                    .traceId("trace-1")
                    .sessionId("session-1")
                    .externalUserId("ADMIN001")
                    .globalUserId("emp-0001")
                    .roles(List.of("admin", "auditor"))
                    .build());

            new DynamicHttpAiTool(entity, new ObjectMapper()).execute(Map.of());

            assertEquals("bzsdk", received.get("project"));
            assertEquals("team-agent", received.get("agent"));
            assertEquals("trace-1", received.get("trace"));
            assertEquals("session-1", received.get("session"));
            assertEquals("ADMIN001", received.get("user"));
            assertEquals("emp-0001", received.get("globalUser"));
            assertEquals("admin,auditor", received.get("roles"));
        } finally {
            ToolExecutionContextHolder.clear();
            server.stop(0);
        }
    }
}
