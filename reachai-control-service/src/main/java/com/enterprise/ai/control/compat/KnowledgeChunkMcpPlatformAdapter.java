package com.enterprise.ai.control.compat;

import com.enterprise.ai.control.mcp.application.port.McpPlatformKnowledgeChunkPort;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

import java.util.Map;

/** Sends one chunk change to the knowledge service and unwraps its ApiResult; owner messages never cross the boundary. */
@Component
@RequiredArgsConstructor
final class KnowledgeChunkMcpPlatformAdapter implements McpPlatformKnowledgeChunkPort {
    private final KnowledgeModelConsoleProxyController proxy;
    private final ObjectMapper json;

    @Override
    public ResponseEntity<?> change(Operation operation, long chunkId, Map<String, Object> body) {
        String path = "/ai/knowledge/chunks/" + chunkId;
        ResponseEntity<byte[]> response;
        try {
            response = switch (operation) {
                case UPDATE -> proxy.knowledgeSend(HttpMethod.PUT, path, Map.of(), json.writeValueAsBytes(body));
                case TOGGLE -> proxy.knowledgeSend(HttpMethod.POST, path + "/toggle",
                        Map.of("enabled", Boolean.TRUE.equals(body.get("enabled")) ? "1" : "0"), null);
                case REEMBED -> proxy.knowledgeSend(HttpMethod.POST, path + "/reembed", Map.of(), null);
            };
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
}
