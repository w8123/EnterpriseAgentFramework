package com.enterprise.ai.control.compat;

import com.enterprise.ai.control.identity.PlatformAuthenticatedSession;
import com.enterprise.ai.control.mcp.application.port.McpPlatformKnowledgeBasePort;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import java.util.Map;

/** Applies the console's knowledge gateway check, then sends one call and unwraps its ApiResult; owner messages stay inside. */
@Component
@RequiredArgsConstructor
final class KnowledgeBaseMcpPlatformAdapter implements McpPlatformKnowledgeBasePort {
    private final KnowledgeModelConsoleProxyController proxy;
    private final ObjectMapper json;

    @Override
    public ResponseEntity<?> call(PlatformAuthenticatedSession actor, Operation operation, String target, Map<String, Object> body) {
        HttpMethod method = operation == Operation.DELETE_BASE || operation == Operation.DELETE_FILE ? HttpMethod.DELETE : HttpMethod.POST;
        String path = switch (operation) {
            case CREATE_BASE -> "/ai/knowledge/base";
            case DELETE_BASE -> "/ai/knowledge/base/" + identifier(target);
            case DELETE_FILE -> "/ai/file/" + identifier(target);
            case RETRIEVAL_TEST -> "/ai/retrieval/test";
        };
        byte[] payload;
        try { payload = body == null ? null : json.writeValueAsBytes(body); }
        catch (Exception invalid) { return ResponseEntity.badRequest().build(); }
        // Refused before anything is sent, so a refusal here is definite.
        HttpStatus denied = proxy.knowledgeWriteDenied(actor, method.name(), path, payload);
        if (denied != null) return ResponseEntity.status(denied == HttpStatus.SERVICE_UNAVAILABLE ? HttpStatus.BAD_GATEWAY : denied).build();
        ResponseEntity<byte[]> response;
        try {
            response = proxy.knowledgeSend(method, path, Map.of(), payload);
        } catch (Exception lost) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
        }
        if (response == null || response.getStatusCode().is5xxServerError()) return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
        if (response.getStatusCode().is4xxClientError()) return ResponseEntity.status(response.getStatusCode()).build();
        try {
            JsonNode envelope = json.readTree(response.getBody());
            if (envelope == null || !envelope.path("code").isInt()) return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
            int code = envelope.path("code").intValue();
            if (code == 200) return ResponseEntity.ok(envelope.path("data").isMissingNode() ? json.nullNode() : envelope.path("data"));
            return code >= 400 && code < 500 ? ResponseEntity.status(code).build() : ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
        } catch (Exception invalid) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
        }
    }

    /** Knowledge base codes and file ids are plain identifiers, so they go into the path without encoding. */
    private static String identifier(String value) {
        if (value == null || !value.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,63}")) throw new IllegalArgumentException("invalid identifier");
        return value;
    }
}
