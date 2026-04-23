package com.enterprise.ai.text.tooling.scanner.manifest;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolManifestTest {

    @Test
    void serializesRequiredManifestFieldsToYaml() {
        ToolManifest manifest = new ToolManifest(
                new ProjectMetadata("legacy-crm", "http://localhost:9001", "/api"),
                List.of(new ToolDefinition(
                        "query_customer",
                        "查询客户信息",
                        "GET",
                        "/customer/search",
                        "GET /api/customer/search",
                        List.of(
                                new ToolParameterDefinition(
                                        "keyword",
                                        "string",
                                        "搜索关键词",
                                        true,
                                        ParameterLocation.QUERY
                                )
                        ),
                        null,
                        "JSON数组，包含客户列表",
                        new ToolSource("openapi", "openapi.yaml#/paths/~1customer~1search/get")
                ))
        );

        String yaml = manifest.toYaml();

        assertTrue(yaml.contains("project:"));
        assertTrue(yaml.contains("tools:"));
        assertTrue(yaml.contains("legacy-crm"));
        assertTrue(yaml.contains("query_customer"));
        assertTrue(yaml.contains("QUERY"));
    }

    @Test
    void roundTripsYamlWithoutLosingContractFields() {
        String yaml = """
                project:
                  name: legacy-crm
                  baseUrl: http://localhost:9001
                  contextPath: /api
                tools:
                  - name: create_order
                    description: 创建销售订单
                    method: POST
                    path: /order
                    endpoint: POST /api/order
                    parameters:
                      - name: body_json
                        type: json
                        description: JSON 请求体，对应 CreateOrderRequest
                        required: true
                        location: BODY
                    requestBodyType: com.demo.api.CreateOrderRequest
                    responseType: JSON对象，包含订单编号
                    source:
                      scanner: openapi
                      location: openapi.yaml#/paths/~1order/post
                """;

        ToolManifest manifest = ToolManifest.fromYaml(yaml);

        assertEquals("legacy-crm", manifest.project().name());
        assertEquals("/api", manifest.project().contextPath());
        assertEquals(1, manifest.tools().size());

        ToolDefinition tool = manifest.tools().get(0);
        assertEquals("create_order", tool.name());
        assertEquals("POST", tool.method());
        assertEquals("/order", tool.path());
        assertEquals("POST /api/order", tool.endpoint());
        assertEquals("com.demo.api.CreateOrderRequest", tool.requestBodyType());
        assertEquals(ParameterLocation.BODY, tool.parameters().get(0).location());
    }

    @Test
    void rejectsMissingProjectName() {
        ToolManifest manifest = new ToolManifest(
                new ProjectMetadata("", "http://localhost:9001", "/api"),
                List.of()
        );

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, manifest::validate);

        assertTrue(exception.getMessage().contains("project.name"));
    }

    @Test
    void rejectsDuplicateToolNames() {
        ToolManifest manifest = new ToolManifest(
                new ProjectMetadata("legacy-crm", "http://localhost:9001", "/api"),
                List.of(
                        new ToolDefinition("same_tool", "A", "GET", "/a", "GET /api/a", List.of(), null, null, null),
                        new ToolDefinition("same_tool", "B", "POST", "/b", "POST /api/b", List.of(), null, null, null)
                )
        );

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, manifest::validate);

        assertTrue(exception.getMessage().contains("duplicate tool.name"));
    }
}
