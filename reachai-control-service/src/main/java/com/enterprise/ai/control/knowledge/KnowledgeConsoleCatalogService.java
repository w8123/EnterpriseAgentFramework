package com.enterprise.ai.control.knowledge;

import com.enterprise.ai.control.identity.PlatformAuthenticatedSession;
import com.enterprise.ai.control.identity.PlatformPermissions;
import com.enterprise.ai.control.mcp.application.port.McpPlatformKnowledgeCatalogPort;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.Map;

/** Shared human/AI read boundary. Scope always comes from the signed owner response, never caller parameters. */
@Service
@RequiredArgsConstructor
public class KnowledgeConsoleCatalogService implements McpPlatformKnowledgeCatalogPort {
    private static final int MAX_RESPONSE_BYTES = 2 * 1024 * 1024;
    private final KnowledgeBizIndexGateway gateway;
    private final ObjectMapper json;
    @Value("${reachai.knowledge.console-ingress.tenant-id:default}")
    private String consoleTenantId = "default";

    @Override
    public ResponseEntity<?> read(PlatformAuthenticatedSession actor, Operation operation, Map<String, Object> parameters) {
        if (actor == null || actor.user() == null || actor.user().getId() == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "platform identity is required");
        }
        if (actor.permissions() == null || !actor.permissions().contains("*") && !actor.permissions().contains(PlatformPermissions.KNOWLEDGE_READ)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "knowledge read permission is required");
        }
        if (operation == Operation.DEPENDENCIES && !actor.hasGlobalPermission(PlatformPermissions.KNOWLEDGE_READ)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "global knowledge read permission is required");
        }
        var command = new LinkedHashMap<>(parameters);
        if (command.containsKey("operation")) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "operation is server-owned");
        command.put("operation", operation.name());
        byte[] body;
        try { body = json.writeValueAsBytes(command); }
        catch (Exception invalid) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid catalog parameters"); }
        var response = gateway.exchange("POST", KnowledgeBizIndexGateway.CATALOG_INTERNAL_ROOT + "/read", consoleTenantId,
                String.valueOf(actor.user().getId()), body, MediaType.APPLICATION_JSON_VALUE);
        if (response == null) return unavailable();
        if (response.status() < 200 || response.status() >= 300) return ResponseEntity.status(response.status()).build();
        try {
            if (response.body() == null || response.body().length == 0 || response.body().length > MAX_RESPONSE_BYTES) return unavailable();
            JsonNode envelope = json.readTree(response.body());
            JsonNode payload = envelope.path("data");
            if (!envelope.path("code").isInt() || envelope.path("code").intValue() != 200
                    || !payload.path("contractVersion").isInt() || payload.path("contractVersion").intValue() != 1
                    || !payload.hasNonNull("data")) return unavailable();
            JsonNode data = payload.get("data");
            if (operation == Operation.BASES) {
                if (!data.isArray()) return unavailable();
                var visible = json.createArrayNode();
                for (JsonNode item : data) {
                    if (canRead(actor, item)) visible.add(item);
                }
                return ResponseEntity.ok(visible);
            }
            if (operation != Operation.DEPENDENCIES && !canRead(actor, payload.path("resource"))) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "knowledge resource is outside the authorized scope");
            }
            if ((operation == Operation.FILES || operation == Operation.CHUNKS || operation == Operation.FILE_CHUNKS || operation == Operation.HIT_LOGS)
                    && !data.isArray()) return unavailable();
            if ((operation == Operation.STATS || operation == Operation.DASHBOARD || operation == Operation.DEPENDENCIES) && !data.isObject()) return unavailable();
            return ResponseEntity.ok(data);
        } catch (ResponseStatusException denied) {
            throw denied;
        } catch (Exception invalid) {
            return unavailable();
        }
    }

    /**
     * One file's chunks plus the owner-signed scope, for writes that must check the caller's knowledge write grant on that
     * exact resource (the knowledge service chunk endpoints themselves are not resource scoped).
     */
    @Override
    public ResponseEntity<?> fileChunksForWrite(PlatformAuthenticatedSession actor, String fileId) {
        var response = read(actor, Operation.FILE_CHUNKS, Map.of("fileId", fileId));
        if (!response.getStatusCode().is2xxSuccessful()) return response;
        JsonNode resource = writableResource(actor, Operation.FILE_CHUNKS, "fileId", fileId);
        if (resource == null) return unavailable();
        var result = new LinkedHashMap<String, Object>();
        result.put("resource", json.convertValue(resource, Object.class));
        result.put("chunks", response.getBody());
        return ResponseEntity.ok(result);
    }

    @Override
    public ResponseEntity<?> baseForWrite(PlatformAuthenticatedSession actor, String kbCode) {
        var response = read(actor, Operation.STATS, Map.of("kbCode", kbCode));
        if (!response.getStatusCode().is2xxSuccessful()) return response;
        JsonNode resource = writableResource(actor, Operation.STATS, "kbCode", kbCode);
        return resource == null ? unavailable() : ResponseEntity.ok(json.convertValue(resource, Object.class));
    }

    /** The owner-signed resource behind one catalog read, or a 403 when the caller lacks knowledge write on it. */
    private JsonNode writableResource(PlatformAuthenticatedSession actor, Operation operation, String key, String value) {
        byte[] body;
        try { body = json.writeValueAsBytes(Map.of("operation", operation.name(), key, value)); }
        catch (Exception invalid) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid catalog parameters"); }
        var owner = gateway.exchange("POST", KnowledgeBizIndexGateway.CATALOG_INTERNAL_ROOT + "/read", consoleTenantId,
                String.valueOf(actor.user().getId()), body, MediaType.APPLICATION_JSON_VALUE);
        if (owner == null || owner.status() < 200 || owner.status() >= 300 || owner.body() == null) return null;
        try {
            JsonNode resource = json.readTree(owner.body()).path("data").path("resource");
            canRead(actor, resource);
            String scope = required(resource, "scope");
            if (!actor.hasResourcePermission(PlatformPermissions.KNOWLEDGE_WRITE, scope, required(resource, "workspaceId"),
                    scope.equals("PROJECT") ? required(resource, "projectCode") : null)) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "knowledge write permission is required for this resource");
            }
            return resource;
        } catch (ResponseStatusException denied) {
            throw denied;
        } catch (Exception invalid) {
            return null;
        }
    }

    private boolean canRead(PlatformAuthenticatedSession actor, JsonNode resource) {
        if (!resource.isObject()) throw new IllegalArgumentException("missing owner scope");
        String scope = required(resource, "scope");
        if (!java.util.Set.of("SHARED", "WORKSPACE", "PROJECT").contains(scope)) throw new IllegalArgumentException("invalid owner scope");
        String workspace = required(resource, "workspaceId");
        String project = scope.equals("PROJECT") ? required(resource, "projectCode") : null;
        return actor.hasResourcePermission(PlatformPermissions.KNOWLEDGE_READ, scope, workspace, project);
    }
    private String required(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (!value.isTextual() || value.asText().isBlank()) throw new IllegalArgumentException("invalid owner scope field");
        return value.asText().trim();
    }
    private ResponseEntity<?> unavailable() { return ResponseEntity.status(HttpStatus.BAD_GATEWAY).build(); }
}
