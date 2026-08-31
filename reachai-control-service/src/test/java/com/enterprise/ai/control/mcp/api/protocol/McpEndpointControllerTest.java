package com.enterprise.ai.control.mcp.api.protocol;

import com.enterprise.ai.control.mcp.application.protocol.McpProtocolApplicationService;
import com.enterprise.ai.control.mcp.application.protocol.McpProtocolApplicationService.McpAuthenticatedSession;
import com.enterprise.ai.control.mcp.domain.McpDomainException;
import com.enterprise.ai.control.mcp.domain.client.McpClient;
import com.enterprise.ai.control.mcp.domain.client.McpClientStatus;
import com.enterprise.ai.control.mcp.domain.publication.McpPublication;
import com.enterprise.ai.control.mcp.domain.publication.McpPublicationItemKind;
import com.enterprise.ai.control.mcp.domain.publication.McpPublicationRevision;
import com.enterprise.ai.control.mcp.domain.publication.McpPublicationStatus;
import com.enterprise.ai.control.mcp.domain.publication.McpToolProjection;
import com.enterprise.ai.control.mcp.infrastructure.McpHubProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class McpEndpointControllerTest {

    private McpProtocolApplicationService service;
    private McpHubProperties properties;
    private McpEndpointController controller;

    @BeforeEach
    void setUp() {
        service = mock(McpProtocolApplicationService.class);
        properties = new McpHubProperties();
        properties.setEnabled(true);
        ObjectMapper objectMapper = new ObjectMapper();
        controller = new McpEndpointController(
                new McpProtocolRequestGuard(properties), service, properties, objectMapper,
                new McpAuditPayloadSanitizer(objectMapper, properties));
    }

    @Test
    void manifestAndGetTransportHonorTheFeatureGate() {
        assertEquals(200, controller.manifest().getStatusCode().value());
        assertEquals(List.of("2025-11-25", "2026-07-28"),
                controller.manifest().getBody().get("supportedProtocolVersions"));
        assertEquals(405, controller.streamNotSupported().getStatusCode().value());
        properties.setEnabled(false);
        assertEquals(404, controller.manifest().getStatusCode().value());
        assertEquals(404, controller.streamNotSupported().getStatusCode().value());
    }

    @Test
    void legacyInitializeAndInitializedNotificationFollowTheLifecycleContract() {
        McpAuthenticatedSession session = session(McpClientStatus.ACTIVE);
        when(service.authenticate("secret-key")).thenReturn(session);
        ResponseEntity<?> initialized = controller.jsonRpc(legacyRequest(false), Map.of(
                "jsonrpc", "2.0", "id", 1, "method", "initialize",
                "params", Map.of("protocolVersion", "2025-11-25")));
        assertEquals(200, initialized.getStatusCode().value());
        assertEquals("2025-11-25", initialized.getHeaders().getFirst("MCP-Protocol-Version"));
        assertEquals("2025-11-25", result(initialized).get("protocolVersion"));

        ResponseEntity<?> notification = controller.jsonRpc(legacyRequest(true), Map.of(
                "jsonrpc", "2.0", "method", "notifications/initialized"));
        assertEquals(202, notification.getStatusCode().value());
        assertNull(notification.getBody());
    }

    @Test
    void mediaTypeFailureUsesHttp415AndSafeJsonRpcMessage() {
        MockHttpServletRequest request = legacyRequest(true);
        request.setContentType("text/plain");
        ResponseEntity<?> response = controller.jsonRpc(request,
                Map.of("jsonrpc", "2.0", "id", 7, "method", "tools/list"));
        assertEquals(415, response.getStatusCode().value());
        assertEquals(-32008, error(response).get("code"));
        assertEquals("MCP request media type is not supported", error(response).get("message"));
        verify(service, never()).authenticate(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void aclDenyUsesThePolicyRangeWithoutLeakingTheSourceReference() {
        McpAuthenticatedSession session = session(McpClientStatus.ACTIVE);
        when(service.authenticate("secret-key")).thenReturn(session);
        when(service.callTool(eq(session), eq("lookup"), anyMap()))
                .thenThrow(new McpDomainException("MCP_TOOL_ACL_DENIED", "secret source orders.lookup"));
        ResponseEntity<?> response = controller.jsonRpc(legacyRequest(true), callBody(8));
        assertEquals(200, response.getStatusCode().value());
        assertEquals(-32002, error(response).get("code"));
        assertEquals("MCP policy denied the request", error(response).get("message"));
    }

    @Test
    void toolBusinessFailureIsAnMcpIsErrorResultAndKeepsRunTraceAuditEvidence() {
        McpAuthenticatedSession session = session(McpClientStatus.ACTIVE);
        when(service.authenticate("secret-key")).thenReturn(session);
        when(service.callTool(eq(session), eq("lookup"), anyMap()))
                .thenReturn(new McpProtocolApplicationService.McpToolCallResult(
                        false, Map.of(), "run-9", "trace-9", "ORDER_BLOCKED"));
        ResponseEntity<?> response = controller.jsonRpc(legacyRequest(true), callBody(9));

        assertEquals(200, response.getStatusCode().value());
        Map<String, Object> result = result(response);
        assertEquals(true, result.get("isError"));
        assertNull(responseBody(response).get("error"));
        ArgumentCaptor<McpProtocolApplicationService.McpAuditRequest> audit =
                ArgumentCaptor.forClass(McpProtocolApplicationService.McpAuditRequest.class);
        verify(service).audit(audit.capture());
        assertFalse(audit.getValue().success());
        assertEquals("RUNTIME", audit.getValue().errorCategory());
        assertEquals("run-9", audit.getValue().runId());
        assertEquals("trace-9", audit.getValue().traceId());
        assertNull(audit.getValue().requestBody());
        assertNull(audit.getValue().responseBody());
    }

    @Test
    void modernDiscoveryValidatesHeadersAndReturnsServerMetadata() {
        McpAuthenticatedSession session = session(McpClientStatus.ACTIVE);
        when(service.authenticate("secret-key")).thenReturn(session);
        MockHttpServletRequest request = modernRequest("server/discover", null);
        Map<String, Object> body = modernBody(11, "server/discover", Map.of());
        ResponseEntity<?> response = controller.jsonRpc(request, body);
        assertEquals(200, response.getStatusCode().value());
        assertEquals("2026-07-28", response.getHeaders().getFirst("MCP-Protocol-Version"));
        assertEquals("complete", result(response).get("resultType"));
        assertTrue(result(response).containsKey("_meta"));
    }

    @Test
    void unexpectedControllerFailureUsesSafeInternalError() {
        when(service.authenticate("secret-key")).thenThrow(new IllegalStateException("database jdbc detail"));

        ResponseEntity<?> response = controller.jsonRpc(legacyRequest(true),
                Map.of("jsonrpc", "2.0", "id", 13, "method", "tools/list"));

        assertEquals(500, response.getStatusCode().value());
        assertEquals(-32603, error(response).get("code"));
        assertEquals("Internal MCP server error", error(response).get("message"));
    }

    private MockHttpServletRequest legacyRequest(boolean withVersion) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/mcp");
        request.setContentType(MediaType.APPLICATION_JSON_VALUE);
        request.addHeader("Accept", "application/json, text/event-stream");
        request.addHeader("Authorization", "Bearer secret-key");
        if (withVersion) request.addHeader("MCP-Protocol-Version", "2025-11-25");
        request.setRemoteAddr("127.0.0.1");
        return request;
    }

    private MockHttpServletRequest modernRequest(String method, String name) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/mcp");
        request.setContentType(MediaType.APPLICATION_JSON_VALUE);
        request.addHeader("Accept", "application/json");
        request.addHeader("Authorization", "Bearer secret-key");
        request.addHeader("MCP-Protocol-Version", "2026-07-28");
        request.addHeader("Mcp-Method", method);
        if (name != null) request.addHeader("Mcp-Name", name);
        request.setRemoteAddr("127.0.0.1");
        return request;
    }

    private Map<String, Object> modernBody(Object id, String method, Map<String, Object> values) {
        Map<String, Object> params = new LinkedHashMap<>(values);
        params.put("_meta", Map.of("io.modelcontextprotocol/protocolVersion", "2026-07-28"));
        return Map.of("jsonrpc", "2.0", "id", id, "method", method, "params", params);
    }

    private Map<String, Object> callBody(Object id) {
        return Map.of("jsonrpc", "2.0", "id", id, "method", "tools/call",
                "params", Map.of("name", "lookup", "arguments", Map.of()));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> responseBody(ResponseEntity<?> response) {
        return (Map<String, Object>) response.getBody();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> error(ResponseEntity<?> response) {
        return (Map<String, Object>) responseBody(response).get("error");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> result(ResponseEntity<?> response) {
        return (Map<String, Object>) responseBody(response).get("result");
    }

    private McpAuthenticatedSession session(McpClientStatus clientStatus) {
        McpToolProjection tool = new McpToolProjection(
                "lookup", "Lookup", "{\"type\":\"object\"}",
                McpPublicationItemKind.CAPABILITY, "orders.lookup", null, "READ");
        McpPublicationRevision revision = new McpPublicationRevision(
                11L, 1L, 1, List.of(tool), "{}", LocalDateTime.now());
        McpPublication publication = new McpPublication(
                1L, "orders", "Orders MCP", McpPublicationStatus.PUBLISHED, 11L, null, null);
        McpClient client = new McpClient(
                2L, 1L, "client", 8L, "orders", "test", "tenant-a",
                "mcp_abcd", "hash", List.of("operator"), List.of(),
                clientStatus, true, null, null, null, null);
        return new McpAuthenticatedSession(client, publication, revision);
    }
}
