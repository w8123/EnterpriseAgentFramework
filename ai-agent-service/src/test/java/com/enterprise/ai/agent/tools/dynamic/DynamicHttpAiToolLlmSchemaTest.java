package com.enterprise.ai.agent.tools.dynamic;

import com.enterprise.ai.agent.agentscope.adapter.AiToolAgentAdapter;
import com.enterprise.ai.agent.skill.ToolExecutionContextHolder;
import com.enterprise.ai.agent.tool.log.ToolExecutionContext;
import com.enterprise.ai.agent.capability.catalog.tool.definition.ToolDefinitionEntity;
import com.enterprise.ai.agent.capability.catalog.tool.definition.ToolDefinitionParameter;
import com.enterprise.ai.reach.sdk.auth.ReachAiInvocationClaims;
import com.enterprise.ai.reach.sdk.auth.ReachAiInvocationToken;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

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
                    "description": "body",
                    "required": true,
                    "location": "BODY",
                    "children": [
                      {
                        "name": "teamName",
                        "type": "string",
                        "description": "team name",
                        "required": false,
                        "location": null
                      },
                      {
                        "name": "page",
                        "type": "integer",
                        "description": "page number",
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
        assertEquals("body", bodyProp.get("description"));

        @SuppressWarnings("unchecked")
        Map<String, Object> nested = (Map<String, Object>) bodyProp.get("properties");
        assertEquals(2, nested.size());
        @SuppressWarnings("unchecked")
        Map<String, Object> teamName = (Map<String, Object>) nested.get("teamName");
        assertEquals("string", teamName.get("type"));
        assertEquals("team name", teamName.get("description"));

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
                "id list",
                true,
                "QUERY",
                List.of(new ToolDefinitionParameter(
                        "item",
                        "string",
                        "single id",
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
            captureHeader(received, exchange, "project", "X-EAF-Project-Code");
            captureHeader(received, exchange, "agent", "X-EAF-Agent-Id");
            captureHeader(received, exchange, "trace", "X-EAF-Trace-Id");
            captureHeader(received, exchange, "session", "X-EAF-Session-Id");
            captureHeader(received, exchange, "user", "X-EAF-User-Id");
            captureHeader(received, exchange, "globalUser", "X-EAF-Global-User-Id");
            captureHeader(received, exchange, "roles", "X-EAF-Roles");
            captureHeader(received, exchange, "reachProject", "X-ReachAI-Project-Code");
            captureHeader(received, exchange, "reachAgent", "X-ReachAI-Agent-Id");
            captureHeader(received, exchange, "reachTrace", "X-ReachAI-Trace-Id");
            captureHeader(received, exchange, "reachSession", "X-ReachAI-Session-Id");
            captureHeader(received, exchange, "reachUser", "X-ReachAI-User-Id");
            captureHeader(received, exchange, "reachGlobalUser", "X-ReachAI-Global-User-Id");
            captureHeader(received, exchange, "reachRoles", "X-ReachAI-Roles");
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
            assertEquals("bzsdk", received.get("reachProject"));
            assertEquals("team-agent", received.get("reachAgent"));
            assertEquals("trace-1", received.get("reachTrace"));
            assertEquals("session-1", received.get("reachSession"));
            assertEquals("ADMIN001", received.get("reachUser"));
            assertEquals("emp-0001", received.get("reachGlobalUser"));
            assertEquals("admin,auditor", received.get("reachRoles"));
        } finally {
            ToolExecutionContextHolder.clear();
            server.stop(0);
        }
    }

    @Test
    void executeSendsInvocationTokenForReachAiCapabilityHostProtocol() throws Exception {
        Map<String, String> received = new ConcurrentHashMap<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/reachai/capabilities/contract.query/invoke", exchange -> {
            captureHeader(received, exchange, "token", "X-ReachAI-Invocation-Token");
            byte[] body = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            ToolDefinitionEntity entity = new ToolDefinitionEntity();
            entity.setName("contract.query");
            entity.setProjectCode("bzsdk");
            entity.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
            entity.setEndpointPath("/reachai/capabilities/contract.query/invoke");
            entity.setHttpMethod("POST");
            entity.setCapabilityMetadataJson("{\"invokeProtocol\":\"REACHAI_CAPABILITY_HTTP\"}");
            entity.setParametersJson("[{\"name\":\"contractNo\",\"type\":\"string\",\"description\":\"Contract number\",\"required\":true,\"location\":\"BODY\"}]");
            ToolExecutionContextHolder.set(ToolExecutionContext.builder()
                    .projectCode("bzsdk")
                    .agentId("team-agent")
                    .traceId("trace-1")
                    .sessionId("session-1")
                    .externalUserId("ADMIN001")
                    .globalUserId("emp-0001")
                    .roles(List.of("admin"))
                    .build());

            DynamicHttpAiTool.InvocationTokenProvider provider = (definition, context) ->
                    ReachAiInvocationToken.sign("secret", ReachAiInvocationClaims.builder()
                            .projectCode(definition.getProjectCode())
                            .appKey("demo-key")
                            .capabilityName(definition.getName())
                            .externalUserId(context.getExternalUserId())
                            .globalUserId(context.getGlobalUserId())
                            .roles(context.getRoles())
                            .agentId(context.getAgentId())
                            .sessionId(context.getSessionId())
                            .traceId(context.getTraceId())
                            .build(), 1_700_000_000_000L, 120);

            new DynamicHttpAiTool(entity, new ObjectMapper(),
                    DynamicHttpAiTool.HttpInvocationExtras.EMPTY, null, provider)
                    .execute(Map.of("contractNo", "HT-001"));

            ReachAiInvocationClaims verified = ReachAiInvocationToken.verify(
                    "secret", received.get("token"), "bzsdk", "contract.query", 1_700_000_030_000L);
            assertEquals("ADMIN001", verified.getExternalUserId());
            assertEquals("team-agent", verified.getAgentId());
            assertEquals("trace-1", verified.getTraceId());
        } finally {
            ToolExecutionContextHolder.clear();
            server.stop(0);
        }
    }

    @Test
    void executeSendsAllArgumentsAsBodyForReachAiCapabilityHostProtocol() throws Exception {
        AtomicReference<String> receivedBody = new AtomicReference<>("");
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/reachai/capabilities/contract.query/invoke", exchange -> {
            receivedBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] body = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            ToolDefinitionEntity entity = new ToolDefinitionEntity();
            entity.setName("contract.query");
            entity.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
            entity.setEndpointPath("/reachai/capabilities/contract.query/invoke");
            entity.setHttpMethod("POST");
            entity.setCapabilityMetadataJson("{\"invokeProtocol\":\"REACHAI_CAPABILITY_HTTP\"}");
            entity.setParametersJson("""
                    [
                      {"name":"contractNo","type":"string","description":"Contract number","required":true,"location":"BODY"},
                      {"name":"includeAttachments","type":"boolean","description":"Include attachments","required":false,"location":"BODY"}
                    ]
                    """);

            new DynamicHttpAiTool(entity, new ObjectMapper()).execute(Map.of(
                    "contractNo", "HT-001",
                    "includeAttachments", true));

            Map<?, ?> body = new ObjectMapper().readValue(receivedBody.get(), Map.class);
            assertEquals("HT-001", body.get("contractNo"));
            assertEquals(Boolean.TRUE, body.get("includeAttachments"));
        } finally {
            server.stop(0);
        }
    }

    private static void captureHeader(Map<String, String> received, HttpExchange exchange, String key, String headerName) {
        String value = exchange.getRequestHeaders().getFirst(headerName);
        received.put(key, value == null ? "" : value);
    }
}
