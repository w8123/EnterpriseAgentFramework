package com.enterprise.ai.control.mcp.api.protocol;

import com.enterprise.ai.control.mcp.domain.McpDomainException;
import com.enterprise.ai.control.mcp.infrastructure.McpHubProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class McpProtocolRequestGuardTest {

    private McpHubProperties properties;
    private McpProtocolRequestGuard guard;

    @BeforeEach
    void setUp() {
        properties = new McpHubProperties();
        properties.setEnabled(true);
        properties.setMaxRequestBytes(1024);
        guard = new McpProtocolRequestGuard(properties);
    }

    @Test
    void endpointIsDefaultOnAndCanBeDisabledWhileOpenEnforcesRequestGuards() {
        McpHubProperties defaults = new McpHubProperties();
        assertTrue(defaults.isEnabled());
        defaults.setEnabled(false);
        McpDomainException disabled = assertThrows(McpDomainException.class,
                () -> new McpProtocolRequestGuard(defaults).open(baseRequest()));
        assertEquals("MCP_PROTOCOL_DISABLED", disabled.code());

        MockHttpServletRequest tooLarge = baseRequest();
        tooLarge.setContent(new byte[2048]);
        assertEquals("MCP_REQUEST_TOO_LARGE", assertThrows(McpDomainException.class,
                () -> guard.open(tooLarge)).code());

        MockHttpServletRequest wrongType = baseRequest();
        wrongType.setContentType("text/plain");
        assertEquals("MCP_CONTENT_TYPE_NOT_SUPPORTED", assertThrows(McpDomainException.class,
                () -> guard.open(wrongType)).code());

        MockHttpServletRequest browser = baseRequest();
        browser.addHeader("Origin", "https://console.example");
        assertEquals("MCP_ORIGIN_FORBIDDEN", assertThrows(McpDomainException.class,
                () -> guard.open(browser)).code());
        properties.setAllowedOrigins(List.of("https://console.example"));
        guard.open(browser);
    }

    @Test
    void bearerTokenIsExtractedWithoutReflectingMalformedCredentials() {
        MockHttpServletRequest request = baseRequest();
        request.addHeader("Authorization", "Bearer mcp_test");
        assertEquals("mcp_test", guard.bearerToken(request));
        assertNull(guard.bearerToken(baseRequest()));
        assertNull(guard.bearerToken(null));
    }

    @Test
    void validatesLegacyInitializeAndInitializedNotification() {
        MockHttpServletRequest initialize = legacyRequest(false);
        var initialized = guard.validate(initialize, Map.of(
                "jsonrpc", "2.0", "id", 1, "method", "initialize",
                "params", Map.of("protocolVersion", "2025-11-25")));
        assertEquals(McpProtocolRequestGuard.ProtocolMode.LEGACY, initialized.mode());
        assertFalse(initialized.notification());

        MockHttpServletRequest notificationRequest = legacyRequest(true);
        var notification = guard.validate(notificationRequest, Map.of(
                "jsonrpc", "2.0", "method", "notifications/initialized"));
        assertTrue(notification.notification());
        assertNull(notification.id());
    }

    @Test
    void validatesModernDiscoverListAndNamedCallEnvelope() {
        var discover = guard.validate(modernRequest("server/discover", null), modernBody(
                1, "server/discover", Map.of()));
        assertEquals(McpProtocolRequestGuard.ProtocolMode.MODERN, discover.mode());

        var list = guard.validate(modernRequest("tools/list", null), modernBody(
                2, "tools/list", Map.of()));
        assertEquals("tools/list", list.method());

        Map<String, Object> callParams = new LinkedHashMap<>();
        callParams.put("name", "orders_lookup");
        callParams.put("arguments", null);
        var call = guard.validate(modernRequest("tools/call", "orders_lookup"), modernBody(
                3, "tools/call", callParams));
        assertEquals("orders_lookup", call.params().get("name"));
        assertTrue(call.params().containsKey("arguments"));
        assertNull(call.params().get("arguments"));
    }

    @Test
    void rejectsModernHeaderMismatchUnsupportedVersionAndMissingRequestId() {
        MockHttpServletRequest mismatch = modernRequest("tools/list", null);
        mismatch.removeHeader(McpProtocolRequestGuard.METHOD_HEADER);
        mismatch.addHeader(McpProtocolRequestGuard.METHOD_HEADER, "tools/call");
        assertEquals("MCP_PROTOCOL_HEADER_MISMATCH", assertThrows(McpDomainException.class,
                () -> guard.validate(mismatch, modernBody(1, "tools/list", Map.of()))).code());

        MockHttpServletRequest unsupported = legacyRequest(true);
        unsupported.removeHeader(McpProtocolRequestGuard.PROTOCOL_VERSION_HEADER);
        unsupported.addHeader(McpProtocolRequestGuard.PROTOCOL_VERSION_HEADER, "2024-11-05");
        assertEquals("MCP_PROTOCOL_VERSION_UNSUPPORTED", assertThrows(McpDomainException.class,
                () -> guard.validate(unsupported,
                        Map.of("jsonrpc", "2.0", "id", 2, "method", "tools/list"))).code());

        assertEquals("MCP_REQUEST_INVALID", assertThrows(McpDomainException.class,
                () -> guard.validate(legacyRequest(true),
                        Map.of("jsonrpc", "2.0", "method", "tools/list"))).code());
    }

    @Test
    void rejectsInvalidJsonRpcMethodAndParamsShape() {
        assertEquals("MCP_REQUEST_INVALID", assertThrows(McpDomainException.class,
                () -> guard.validate(legacyRequest(true),
                        Map.of("jsonrpc", "1.0", "id", 1, "method", "tools/list"))).code());
        assertEquals("MCP_METHOD_NOT_FOUND", assertThrows(McpDomainException.class,
                () -> guard.validate(legacyRequest(true),
                        Map.of("jsonrpc", "2.0", "id", 1, "method", "prompts/list"))).code());
        assertEquals("MCP_REQUEST_INVALID", assertThrows(McpDomainException.class,
                () -> guard.validate(legacyRequest(true),
                        Map.of("jsonrpc", "2.0", "id", 1, "method", "tools/list", "params", "bad"))).code());
    }

    private MockHttpServletRequest baseRequest() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setContentType("application/json");
        request.addHeader("Accept", "application/json, text/event-stream");
        return request;
    }

    private MockHttpServletRequest legacyRequest(boolean withVersionHeader) {
        MockHttpServletRequest request = baseRequest();
        if (withVersionHeader) {
            request.addHeader(McpProtocolRequestGuard.PROTOCOL_VERSION_HEADER, "2025-11-25");
        }
        return request;
    }

    private MockHttpServletRequest modernRequest(String method, String name) {
        MockHttpServletRequest request = baseRequest();
        request.removeHeader("Accept");
        request.addHeader("Accept", "application/json");
        request.addHeader(McpProtocolRequestGuard.PROTOCOL_VERSION_HEADER, "2026-07-28");
        request.addHeader(McpProtocolRequestGuard.METHOD_HEADER, method);
        if (name != null) request.addHeader(McpProtocolRequestGuard.NAME_HEADER, name);
        return request;
    }

    private Map<String, Object> modernBody(Object id, String method, Map<String, Object> values) {
        Map<String, Object> params = new LinkedHashMap<>(values);
        params.put("_meta", Map.of(McpProtocolRequestGuard.MODERN_PROTOCOL_META, "2026-07-28"));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("jsonrpc", "2.0");
        body.put("id", id);
        body.put("method", method);
        body.put("params", params);
        return body;
    }
}
