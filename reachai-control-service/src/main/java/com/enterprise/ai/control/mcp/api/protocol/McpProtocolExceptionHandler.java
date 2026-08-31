package com.enterprise.ai.control.mcp.api.protocol;

import com.enterprise.ai.control.mcp.application.protocol.McpProtocolApplicationService;
import com.fasterxml.jackson.core.JsonParseException;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;

/** Converts pre-controller JSON parse failures into a safe, audited JSON-RPC response. */
@RestControllerAdvice(assignableTypes = McpEndpointController.class)
@RequiredArgsConstructor
public class McpProtocolExceptionHandler {

    private final McpProtocolApplicationService protocolService;

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> malformedJson(HttpMessageNotReadableException failure,
                                                              HttpServletRequest request) {
        boolean parseFailure = isParseFailure(failure);
        int rpcCode = parseFailure ? -32700 : -32600;
        String errorCode = parseFailure ? "MCP_JSON_PARSE_ERROR" : "MCP_REQUEST_INVALID";
        String message = parseFailure ? "Invalid MCP JSON payload" : "Invalid MCP protocol request";
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("jsonrpc", "2.0");
        response.put("id", null);
        response.put("error", Map.of("code", rpcCode, "message", message));
        protocolService.audit(new McpProtocolApplicationService.McpAuditRequest(
                null, "unknown", null, false, 0L, McpProtocolError.PROTOCOL.name(),
                null, null, errorCode, null, null,
                request == null ? null : request.getRemoteAddr()));
        return ResponseEntity.badRequest().body(response);
    }

    private boolean isParseFailure(Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof JsonParseException) return true;
            current = current.getCause();
        }
        return false;
    }
}
