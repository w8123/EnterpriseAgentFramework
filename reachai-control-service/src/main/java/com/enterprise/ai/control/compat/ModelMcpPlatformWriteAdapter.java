package com.enterprise.ai.control.compat;

import com.enterprise.ai.control.mcp.application.port.McpPlatformModelWritePort;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
@RequiredArgsConstructor
final class ModelMcpPlatformWriteAdapter implements McpPlatformModelWritePort {
    private final KnowledgeModelConsoleProxyController proxy;
    private final ObjectMapper json;

    @Override
    public ResponseEntity<?> change(Operation operation, String modelId, Map<String, Object> body) {
        String path = "/model/instances/" + modelId;
        try {
            return unwrap(switch (operation) {
                case UPDATE -> proxy.modelSend(HttpMethod.PUT, path, json.writeValueAsBytes(body));
                case ARCHIVE -> proxy.modelSend(HttpMethod.POST, path + "/archive", null);
                case TEST -> proxy.modelSend(HttpMethod.POST, path + "/test", null);
            });
        } catch (Exception lost) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
        }
    }

    @Override
    public ResponseEntity<?> draft(DraftOperation operation, Map<String, Object> body) {
        try {
            return unwrap(proxy.modelSend(HttpMethod.POST, operation == DraftOperation.TEST ? "/model/instances/test-draft"
                    : "/model/instances/discover-models", json.writeValueAsBytes(body)));
        } catch (Exception lost) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
        }
    }

    @Override
    public ResponseEntity<?> templates() { return unwrap(proxy.modelSend(HttpMethod.GET, "/model/templates", null)); }

    @Override
    public ResponseEntity<?> template(String templateId) {
        return unwrap(proxy.modelSend(HttpMethod.GET, "/model/templates/" + templateId, null));
    }

    @Override
    public ResponseEntity<?> account(AccountOperation operation, String accountId, Map<String, String> query, Object body) {
        String base = "/model/instances/provider-accounts";
        try {
            byte[] json = body == null ? null : this.json.writeValueAsBytes(body);
            return unwrap(switch (operation) {
                case LIST -> proxy.modelSend(HttpMethod.GET, org.springframework.web.util.UriComponentsBuilder.fromPath(base)
                        .queryParamIfPresent("provider", java.util.Optional.ofNullable(query.get("provider"))).encode().toUriString(), null);
                case GET -> proxy.modelSend(HttpMethod.GET, base + "/" + accountId, null);
                case CREATE -> proxy.modelSend(HttpMethod.POST, base, json);
                case UPDATE -> proxy.modelSend(HttpMethod.PUT, base + "/" + accountId, json);
                case DELETE -> proxy.modelSend(HttpMethod.DELETE, base + "/" + accountId, null);
                case DISCOVER_DRAFT -> proxy.modelSend(HttpMethod.POST, base + "/discover", json);
                case DISCOVER_SAVED -> proxy.modelSend(HttpMethod.POST, base + "/" + accountId + "/models", null);
                case CREATE_INSTANCES -> proxy.modelSend(HttpMethod.POST, base + "/" + accountId + "/instances", json);
            });
        } catch (Exception lost) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
        }
    }

    @Override
    public ResponseEntity<?> catalog(CatalogOperation operation, Object body) {
        try {
            return unwrap(switch (operation) {
                case STATUS -> proxy.modelSend(HttpMethod.GET, "/model/catalog/status", null);
                case SETTINGS -> proxy.modelSend(HttpMethod.PUT, "/model/catalog/settings", json.writeValueAsBytes(body));
                case SYNC -> proxy.modelSend(HttpMethod.POST, "/model/catalog/sync", null);
            });
        } catch (Exception lost) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
        }
    }

    private ResponseEntity<?> unwrap(ResponseEntity<byte[]> response) {
        if (response == null || response.getStatusCode().is5xxServerError()) return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
        if (response.getStatusCode().is4xxClientError()) return ResponseEntity.status(response.getStatusCode()).build();
        try {
            JsonNode envelope = json.readTree(response.getBody());
            if (envelope == null || !envelope.path("code").isInt()) return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
            int code = envelope.path("code").intValue();
            if (code == 200) return ResponseEntity.ok(envelope.path("data"));
            return code >= 400 && code < 500 ? ResponseEntity.status(code).build() : ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
        } catch (Exception invalid) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
        }
    }
}
