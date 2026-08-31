package com.enterprise.ai.control.mcp.api.protocol;

import com.enterprise.ai.control.mcp.api.protocol.McpProtocolRequestGuard.ProtocolMode;
import com.enterprise.ai.control.mcp.application.protocol.McpProtocolApplicationService;
import com.enterprise.ai.control.mcp.application.protocol.McpProtocolApplicationService.McpAuthenticatedSession;
import com.enterprise.ai.control.mcp.application.protocol.McpProtocolApplicationService.McpToolCallResult;
import com.enterprise.ai.control.mcp.domain.McpDomainException;
import com.enterprise.ai.control.mcp.infrastructure.McpHubProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Dual MCP protocol adapter over the frozen publication application service. */
@RestController
public class McpEndpointController {

    private static final String SERVER_INFO_META = "io.modelcontextprotocol/serverInfo";

    private final McpProtocolRequestGuard guard;
    private final McpProtocolApplicationService service;
    private final McpHubProperties properties;
    private final ObjectMapper objectMapper;
    private final McpAuditPayloadSanitizer auditPayloadSanitizer;

    public McpEndpointController(McpProtocolRequestGuard guard,
                                 McpProtocolApplicationService service,
                                 McpHubProperties properties,
                                 ObjectMapper objectMapper,
                                 McpAuditPayloadSanitizer auditPayloadSanitizer) {
        this.guard = guard;
        this.service = service;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.auditPayloadSanitizer = auditPayloadSanitizer;
    }

    @GetMapping("/mcp/manifest")
    public ResponseEntity<Map<String, Object>> manifest() {
        if (!properties.isEnabled()) return ResponseEntity.notFound().build();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", properties.getServerName());
        body.put("supportedProtocolVersions", properties.supportedProtocolVersions());
        body.put("transport", Map.of("type", "streamable-http", "url", "/mcp"));
        body.put("capabilities", capabilities());
        return ResponseEntity.ok(body);
    }

    /** No resumable SSE stream is offered; POST remains the single endpoint. */
    @GetMapping({"/mcp", "/mcp/jsonrpc"})
    public ResponseEntity<Map<String, Object>> streamNotSupported() {
        if (!properties.isEnabled()) return ResponseEntity.notFound().build();
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
                .header(HttpHeaders.ALLOW, "POST")
                .body(Map.of("code", "MCP_SSE_NOT_SUPPORTED"));
    }

    @PostMapping({"/mcp", "/mcp/jsonrpc"})
    public ResponseEntity<?> jsonRpc(HttpServletRequest httpRequest,
                                     @RequestBody(required = false) Map<String, Object> body) {
        long startedAt = System.nanoTime();
        String remoteIp = httpRequest.getRemoteAddr();
        McpAuthenticatedSession session = null;
        String method = text(body == null ? null : body.get("method"));
        String toolName = bodyToolName(body);
        try {
            guard.open(httpRequest);
            McpProtocolRequestGuard.ValidatedRequest request = guard.validate(httpRequest, body);
            session = service.authenticate(guard.bearerToken(httpRequest));
            method = request.method();
            toolName = text(request.params().get("name"));
            DispatchResult dispatched = dispatch(session, request);
            audit(session, method, toolName, body, dispatched.response(),
                    dispatched.executionSuccess(), dispatched.errorCategory(), dispatched.errorCode(),
                    dispatched.traceId(), dispatched.runId(), startedAt, remoteIp);
            if (dispatched.notification()) {
                return ResponseEntity.accepted()
                        .header(McpProtocolRequestGuard.PROTOCOL_VERSION_HEADER,
                                protocolVersion(request.mode()))
                        .build();
            }
            return ResponseEntity.ok()
                    .header(McpProtocolRequestGuard.PROTOCOL_VERSION_HEADER,
                            protocolVersion(request.mode()))
                    .body(dispatched.response());
        } catch (McpDomainException failure) {
            McpProtocolError category = McpProtocolError.of(failure);
            Map<String, Object> response = error(
                    extractId(body), category.jsonRpcCode(failure.code()), category.safeMessage());
            audit(session, method, toolName, body, response, false, category.name(), failure.code(),
                    null, null, startedAt, remoteIp);
            return ResponseEntity.status(category.httpStatus()).body(response);
        } catch (RuntimeException unexpected) {
            McpProtocolError category = McpProtocolError.INTERNAL;
            Map<String, Object> response = error(
                    extractId(body), category.jsonRpcCode("MCP_INTERNAL_ERROR"), category.safeMessage());
            audit(session, method, toolName, body, response, false, category.name(),
                    "MCP_INTERNAL_ERROR", null, null, startedAt, remoteIp);
            return ResponseEntity.status(category.httpStatus()).body(response);
        }
    }

    private DispatchResult dispatch(McpAuthenticatedSession session,
                                    McpProtocolRequestGuard.ValidatedRequest request) {
        return switch (request.method()) {
            case "initialize" -> ok(success(request.id(), initializeResult()), request);
            case "notifications/initialized" -> DispatchResult.notificationResult();
            case "server/discover" -> ok(success(request.id(), discoveryResult()), request);
            case "tools/list" -> {
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("tools", service.visibleTools(session));
                addModernMeta(result, request.mode());
                yield ok(success(request.id(), result), request);
            }
            case "tools/call" -> toolsCall(session, request);
            default -> throw new McpDomainException("MCP_METHOD_NOT_FOUND", "method not found");
        };
    }

    private DispatchResult toolsCall(McpAuthenticatedSession session,
                                     McpProtocolRequestGuard.ValidatedRequest request) {
        McpToolCallResult result = service.callTool(
                session, text(request.params().get("name")), arguments(request.params().get("arguments")));
        Map<String, Object> payload = new LinkedHashMap<>();
        if (result.success()) {
            payload.put("content", List.of(Map.of("type", "text", "text", toJson(result.output()))));
            payload.put("structuredContent", result.output());
            payload.put("isError", false);
        } else {
            String code = result.errorCode() == null ? "MCP_TOOL_EXECUTION_FAILED" : result.errorCode();
            payload.put("content", List.of(Map.of("type", "text", "text", code)));
            payload.put("structuredContent", Map.of("errorCode", code));
            payload.put("isError", true);
        }
        Map<String, Object> meta = new LinkedHashMap<>();
        putIfPresent(meta, "reachai/runId", result.runId());
        putIfPresent(meta, "reachai/traceId", result.traceId());
        if (request.mode() == ProtocolMode.MODERN) meta.put(SERVER_INFO_META, serverInfo());
        if (!meta.isEmpty()) payload.put("_meta", meta);
        return new DispatchResult(success(request.id(), payload), false, result.success(),
                result.success() ? null : McpProtocolError.RUNTIME.name(), result.errorCode(),
                result.traceId(), result.runId());
    }

    private Map<String, Object> initializeResult() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("protocolVersion", properties.getLegacyProtocolVersion());
        result.put("capabilities", capabilities());
        result.put("serverInfo", serverInfo());
        return result;
    }

    private Map<String, Object> discoveryResult() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("resultType", "complete");
        result.put("supportedVersions", List.of(properties.getModernProtocolVersion()));
        result.put("capabilities", capabilities());
        result.put("ttlMs", 300_000);
        result.put("cacheScope", "private");
        result.put("_meta", Map.of(SERVER_INFO_META, serverInfo()));
        return result;
    }

    private Map<String, Object> capabilities() {
        return Map.of("tools", Map.of("listChanged", false));
    }

    private Map<String, Object> serverInfo() {
        return Map.of("name", properties.getServerName(), "version", properties.getServerVersion());
    }

    private void addModernMeta(Map<String, Object> result, ProtocolMode mode) {
        if (mode == ProtocolMode.MODERN) result.put("_meta", Map.of(SERVER_INFO_META, serverInfo()));
    }

    private DispatchResult ok(Map<String, Object> response,
                              McpProtocolRequestGuard.ValidatedRequest request) {
        return new DispatchResult(response, request.notification(), true, null, null, null, null);
    }

    private void audit(McpAuthenticatedSession session, String method, String toolName,
                       Object requestBody, Object responseBody, boolean success, String errorCategory,
                       String errorCode, String traceId, String runId, long startedAt, String remoteIp) {
        service.audit(new McpProtocolApplicationService.McpAuditRequest(
                session, method, toolName, success,
                (System.nanoTime() - startedAt) / 1_000_000L,
                errorCategory,
                auditPayloadSanitizer.capture(requestBody),
                auditPayloadSanitizer.capture(responseBody),
                errorCode, traceId, runId, remoteIp));
    }

    private Map<String, Object> success(Object id, Object result) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("jsonrpc", "2.0");
        response.put("id", id);
        response.put("result", result);
        return response;
    }

    private Map<String, Object> error(Object id, int code, String message) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("jsonrpc", "2.0");
        response.put("id", id);
        response.put("error", Map.of("code", code, "message", message));
        return response;
    }

    private Map<String, Object> arguments(Object value) {
        if (value == null) return Map.of();
        if (!(value instanceof Map<?, ?> raw)) {
            throw new McpDomainException("MCP_PARAMS_INVALID",
                    "tools/call params.arguments must be an object");
        }
        Map<String, Object> arguments = new LinkedHashMap<>();
        raw.forEach((key, item) -> {
            if (key != null) arguments.put(String.valueOf(key), item);
        });
        return arguments;
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value == null ? Map.of() : value);
        } catch (JsonProcessingException failure) {
            return "{}";
        }
    }

    private String protocolVersion(ProtocolMode mode) {
        return mode == ProtocolMode.MODERN
                ? properties.getModernProtocolVersion() : properties.getLegacyProtocolVersion();
    }

    private static Object extractId(Map<String, Object> body) {
        return body == null ? null : body.get("id");
    }

    private static String bodyToolName(Map<String, Object> body) {
        if (body == null || !(body.get("params") instanceof Map<?, ?> params)) return null;
        return text(params.get("name"));
    }

    private static void putIfPresent(Map<String, Object> target, String key, Object value) {
        if (value != null) target.put(key, value);
    }

    private static String text(Object value) {
        if (value == null) return null;
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
    }

    private record DispatchResult(Map<String, Object> response, boolean notification,
                                  boolean executionSuccess, String errorCategory, String errorCode,
                                  String traceId, String runId) {
        private static DispatchResult notificationResult() {
            return new DispatchResult(null, true, true, null, null, null, null);
        }
    }
}
