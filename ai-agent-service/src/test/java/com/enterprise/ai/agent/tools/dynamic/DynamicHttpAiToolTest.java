package com.enterprise.ai.agent.tools.dynamic;

import com.enterprise.ai.agent.tools.definition.ToolDefinitionEntity;
import com.enterprise.ai.agent.tools.definition.ToolDefinitionParameter;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DynamicHttpAiToolTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void executesGetToolWithPathAndQueryParameters() throws Exception {
        AtomicReference<String> requestPath = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/api/customer/42/detail", exchange -> {
            requestPath.set(exchange.getRequestURI().toString());
            byte[] response = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();

        try {
            ToolDefinitionEntity definition = new ToolDefinitionEntity();
            definition.setName("query_customer");
            definition.setDescription("查询客户");
            definition.setHttpMethod("GET");
            definition.setBaseUrl("http://localhost:" + server.getAddress().getPort());
            definition.setContextPath("/api");
            definition.setEndpointPath("/customer/{customerId}/detail");
            definition.setParametersJson(objectMapper.writeValueAsString(List.of(
                    new ToolDefinitionParameter("customerId", "string", "客户ID", true, "PATH"),
                    new ToolDefinitionParameter("keyword", "string", "关键词", false, "QUERY")
            )));

            DynamicHttpAiTool tool = new DynamicHttpAiTool(definition, objectMapper);

            Object result = tool.execute(Map.of("customerId", "42", "keyword", "张三"));

            assertEquals("{\"ok\":true}", result);
            assertEquals("/api/customer/42/detail?keyword=%E5%BC%A0%E4%B8%89", requestPath.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void executesPostToolWithJsonBody() throws Exception {
        AtomicReference<String> requestBody = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/api/orders", exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] response = "{\"status\":\"created\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();

        try {
            ToolDefinitionEntity definition = new ToolDefinitionEntity();
            definition.setName("create_order");
            definition.setDescription("创建订单");
            definition.setHttpMethod("POST");
            definition.setBaseUrl("http://localhost:" + server.getAddress().getPort());
            definition.setContextPath("/api");
            definition.setEndpointPath("/orders");
            definition.setParametersJson(objectMapper.writeValueAsString(List.of(
                    new ToolDefinitionParameter("body_json", "json", "请求体", true, "BODY")
            )));

            DynamicHttpAiTool tool = new DynamicHttpAiTool(definition, objectMapper);

            Object result = tool.execute(Map.of("body_json", "{\"customerId\":\"C1\"}"));

            assertEquals("{\"status\":\"created\"}", result);
            assertEquals("{\"customerId\":\"C1\"}", requestBody.get());
        } finally {
            server.stop(0);
        }
    }
}
