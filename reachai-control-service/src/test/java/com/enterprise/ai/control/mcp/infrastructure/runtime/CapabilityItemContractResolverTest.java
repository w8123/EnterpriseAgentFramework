package com.enterprise.ai.control.mcp.infrastructure.runtime;

import com.enterprise.ai.control.client.capability.CapabilityProxyClient;
import com.enterprise.ai.control.mcp.application.port.McpContractResolutionException;
import com.enterprise.ai.control.mcp.domain.publication.McpPublicationItemKind;
import com.enterprise.ai.control.mcp.domain.publication.McpToolProjection;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CapabilityItemContractResolverTest {

    private CapabilityProxyClient capabilityProxyClient;
    private CapabilityItemContractResolver resolver;

    @BeforeEach
    void setUp() {
        capabilityProxyClient = mock(CapabilityProxyClient.class);
        resolver = new CapabilityItemContractResolver(capabilityProxyClient, new ObjectMapper());
    }

    private Map<String, Object> definition(String name,
                                           String title,
                                           String aiDescription,
                                           String parametersJson,
                                           String sideEffect) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", name);
        body.put("title", title);
        body.put("description", "raw description");
        body.put("aiDescription", aiDescription);
        body.put("parametersJson", parametersJson);
        body.put("sideEffect", sideEffect);
        body.put("enabled", Boolean.TRUE);
        body.put("qualifiedName", "orders:" + name);
        return body;
    }

    @Test
    void supportedKindIsCapability() {
        assertEquals(McpPublicationItemKind.CAPABILITY, resolver.supportedKind());
    }

    @Test
    void parameterArrayBecomesObjectSchemaWithPropertiesAndRequired() {
        String parametersJson = """
                [
                  {"name":"orderId","type":"string","description":"订单 ID","required":true},
                  {"name":"reason","type":"string"}
                ]
                """;
        when(capabilityProxyClient.getToolDefinition("orders:order.cancel"))
                .thenReturn(ResponseEntity.ok(definition("order_cancel", "取消订单",
                        "AI 取消订单说明", parametersJson, "IRREVERSIBLE")));

        McpToolProjection projection = resolver.resolve("orders:order.cancel");

        assertEquals("order_cancel", projection.name());
        assertEquals("AI 取消订单说明", projection.description());
        assertEquals("IRREVERSIBLE", projection.riskLevel());
        assertEquals(McpPublicationItemKind.CAPABILITY, projection.sourceKind());
        assertEquals("orders:order.cancel", projection.sourceRef());
        assertTrue(projection.workflowVersionId() == null);
        String expectedSchema = """
                {"type":"object","properties":{"orderId":{"type":"string","description":"订单 ID"},"reason":{"type":"string"}},"required":["orderId"]}""";
        assertEquals(expectedSchema, projection.inputSchemaJson());
    }

    @Test
    void nestedParameterChildrenBecomeObjectProperties() {
        String parametersJson = """
                [
                  {"name":"query","type":"object","required":true,"children":[
                    {"name":"keyword","type":"string","required":true},
                    {"name":"limit","type":"integer"}
                  ]}
                ]
                """;
        when(capabilityProxyClient.getToolDefinition("orders:order.search"))
                .thenReturn(ResponseEntity.ok(definition("order_search", "搜索订单", null,
                        parametersJson, "READ_ONLY")));

        McpToolProjection projection = resolver.resolve("orders:order.search");

        String expectedSchema = """
                {"type":"object","properties":{"query":{"type":"object","properties":{"keyword":{"type":"string"},"limit":{"type":"integer"}},"required":["keyword"]}},"required":["query"]}""";
        assertEquals(expectedSchema, projection.inputSchemaJson());
        assertEquals("READ", projection.riskLevel());
    }

    @Test
    void jsonSchemaObjectParametersPassThroughVerbatim() {
        String parametersJson = """
                {"type":"object","properties":{"amount":{"type":"number"}},"required":["amount"]}
                """;
        when(capabilityProxyClient.getToolDefinition("orders:order.refund"))
                .thenReturn(ResponseEntity.ok(definition("order_refund", "退款", null,
                        parametersJson, "IDEMPOTENT_WRITE")));

        McpToolProjection projection = resolver.resolve("orders:order.refund");

        assertEquals("{\"type\":\"object\",\"properties\":{\"amount\":{\"type\":\"number\"}},\"required\":[\"amount\"]}",
                projection.inputSchemaJson());
        assertEquals("WRITE", projection.riskLevel());
    }

    @Test
    void missingAiDescriptionFallsBackToTitle() {
        when(capabilityProxyClient.getToolDefinition("orders:order.detail"))
                .thenReturn(ResponseEntity.ok(definition("order_detail", "订单详情", null,
                        "[{\"name\":\"id\",\"type\":\"string\",\"required\":true}]", "NONE")));

        McpToolProjection projection = resolver.resolve("orders:order.detail");

        assertEquals("订单详情", projection.description());
        assertEquals("READ", projection.riskLevel());
    }

    @Test
    void missingAiDescriptionAndTitleFallsBackToRawDescription() {
        when(capabilityProxyClient.getToolDefinition("orders:order.note"))
                .thenReturn(ResponseEntity.ok(definition("order_note", null, null,
                        "[{\"name\":\"id\",\"type\":\"string\",\"required\":true}]", "WRITE")));

        McpToolProjection projection = resolver.resolve("orders:order.note");

        assertEquals("raw description", projection.description());
    }

    @Test
    void blankSideEffectDefaultsToWrite() {
        when(capabilityProxyClient.getToolDefinition("orders:order.update"))
                .thenReturn(ResponseEntity.ok(definition("order_update", "更新订单", null,
                        "[{\"name\":\"id\",\"type\":\"string\",\"required\":true}]", "")));

        assertEquals("WRITE", resolver.resolve("orders:order.update").riskLevel());
    }

    @Test
    void toolNameWithInvalidCharactersIsSanitized() {
        when(capabilityProxyClient.getToolDefinition("orders:team.memory.resolve"))
                .thenReturn(ResponseEntity.ok(definition("team.memory.resolve", "团队记忆", null,
                        "[{\"name\":\"key\",\"type\":\"string\",\"required\":true}]", "READ_ONLY")));

        McpToolProjection projection = resolver.resolve("orders:team.memory.resolve");

        assertEquals("team_memory_resolve", projection.name());
    }

    @Test
    void missingParametersJsonFailsResolution() {
        when(capabilityProxyClient.getToolDefinition("orders:order.broken"))
                .thenReturn(ResponseEntity.ok(definition("order_broken", "坏契约", null, null, "WRITE")));

        McpContractResolutionException exception = assertThrows(McpContractResolutionException.class,
                () -> resolver.resolve("orders:order.broken"));
        assertEquals("MCP_CAPABILITY_SCHEMA_UNRESOLVABLE", exception.code());
        assertEquals("orders:order.broken", exception.sourceRef());
    }

    @Test
    void disabledToolFailsResolution() {
        Map<String, Object> body = definition("order_frozen", "冻结订单", null,
                "[{\"name\":\"id\",\"type\":\"string\"}]", "WRITE");
        body.put("enabled", Boolean.FALSE);
        when(capabilityProxyClient.getToolDefinition("orders:order.frozen"))
                .thenReturn(ResponseEntity.ok(body));

        McpContractResolutionException exception = assertThrows(McpContractResolutionException.class,
                () -> resolver.resolve("orders:order.frozen"));
        assertEquals("MCP_CAPABILITY_TOOL_DISABLED", exception.code());
    }

    @Test
    void non2xxLookupResponseReportsToolNotFound() {
        when(capabilityProxyClient.getToolDefinition("orders:order.missing"))
                .thenReturn(ResponseEntity.status(404).build());

        McpContractResolutionException exception = assertThrows(McpContractResolutionException.class,
                () -> resolver.resolve("orders:order.missing"));
        assertEquals("MCP_CAPABILITY_TOOL_NOT_FOUND", exception.code());
        assertTrue(exception.getMessage().contains("orders:order.missing"));
    }

    @Test
    void feignNotFoundReportsToolNotFound() {
        feign.Request request = feign.Request.create(
                feign.Request.HttpMethod.GET,
                "/internal/capability/tools/orders:order.missing",
                Map.of(),
                null,
                java.nio.charset.StandardCharsets.UTF_8,
                null);
        when(capabilityProxyClient.getToolDefinition(anyString()))
                .thenThrow(new feign.FeignException.NotFound("not found", request, null, Map.of()));

        McpContractResolutionException exception = assertThrows(McpContractResolutionException.class,
                () -> resolver.resolve("orders:order.missing"));
        assertEquals("MCP_CAPABILITY_TOOL_NOT_FOUND", exception.code());
    }

    @Test
    void blankSourceRefFailsResolution() {
        McpContractResolutionException exception = assertThrows(McpContractResolutionException.class,
                () -> resolver.resolve("  "));
        assertEquals("MCP_REQUIRED_FIELD", exception.code());
    }
}
