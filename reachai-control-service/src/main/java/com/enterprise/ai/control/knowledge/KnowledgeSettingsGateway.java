package com.enterprise.ai.control.knowledge;

import com.enterprise.ai.control.identity.PlatformAuthenticatedSession;
import com.enterprise.ai.control.identity.PlatformAuthAuditService;
import com.enterprise.ai.control.identity.PlatformPermissions;
import com.enterprise.ai.control.mcp.application.port.McpPlatformKnowledgeSettingsPort;
import com.enterprise.ai.control.mcp.application.port.McpPlatformModelCatalogPort;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

/** Resource-authorized settings boundary, with model access checked through the same scoped console projection. */
@Service
@RequiredArgsConstructor
public class KnowledgeSettingsGateway implements McpPlatformKnowledgeSettingsPort {
    private static final java.util.Set<String> SETTINGS_FIELDS = java.util.Set.of("name", "description", "embeddingModelInstanceId", "llmModelInstanceId",
            "rerankModelInstanceId", "accessMode", "chunkSize", "chunkOverlap", "splitType", "searchMode", "topK", "similarityThreshold", "rerankEnabled", "vectorWeight", "keywordWeight");
    private final KnowledgeBizIndexGateway gateway;
    private final McpPlatformModelCatalogPort models;
    private final PlatformAuthAuditService audit;
    private final ObjectMapper json;
    @Value("${reachai.knowledge.console-ingress.tenant-id:default}")
    private String consoleTenantId = "default";

    @Override
    public ResponseEntity<?> context(PlatformAuthenticatedSession actor, String kbCode) {
        requireActor(actor);
        var response = exchange(actor, "context", Map.of("kbCode", code(kbCode)));
        if (!response.getStatusCode().is2xxSuccessful()) return response;
        JsonNode context = (JsonNode) response.getBody();
        checkContext(context, kbCode);
        require(actor, PlatformPermissions.KNOWLEDGE_READ, context.path("resource"));
        return response;
    }

    @Override
    public ResponseEntity<?> preview(PlatformAuthenticatedSession actor, String kbCode, String revision, Map<String, Object> changes) {
        var response = context(actor, kbCode);
        if (!response.getStatusCode().is2xxSuccessful()) return response;
        JsonNode current = (JsonNode) response.getBody();
        require(actor, PlatformPermissions.KNOWLEDGE_WRITE, current.path("resource"));
        if (revision == null || !revision.equals(current.path("revision").asText()) || !revision.matches("[0-9a-f]{64}")) throw changed();
        if (changes == null || changes.isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "settings changes are required");
        for (String field : java.util.List.of("embeddingModelInstanceId", "llmModelInstanceId", "rerankModelInstanceId")) {
            if (!changes.containsKey(field) || java.util.Objects.equals(json.valueToTree(changes.get(field)), current.path("before").get(field))) continue;
            Object modelId = changes.get(field);
            if (field.equals("rerankModelInstanceId") && (modelId == null || modelId.equals(""))) continue;
            if (!(modelId instanceof String id) || id.isBlank()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "model instance is required");
            var modelResponse = models.get(actor, id);
            if (modelResponse == null || !modelResponse.getStatusCode().is2xxSuccessful()) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "model instance is unavailable in the caller scope");
            var model = json.valueToTree(modelResponse.getBody()).path("data");
            String type = field.equals("embeddingModelInstanceId") ? "EMBEDDING" : field.equals("llmModelInstanceId") ? "LLM" : "RERANKER";
            if (!id.equals(model.path("id").asText()) || !type.equals(model.path("modelType").asText()) || !"ACTIVE".equals(model.path("status").asText())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "an active model of the expected type is required");
            }
        }
        var preview = exchange(actor, "preview", Map.of("kbCode", kbCode, "baseRevision", revision, "changes", changes));
        if (!preview.getStatusCode().is2xxSuccessful()) return preview;
        var proposed = (JsonNode) preview.getBody();
        checkContext(proposed, kbCode);
        if (!current.path("knowledgeBaseId").equals(proposed.path("knowledgeBaseId")) || !current.path("revision").equals(proposed.path("revision")) || !current.path("resource").equals(proposed.path("resource"))
                || !current.path("before").equals(proposed.path("before")) || !completeSettings(proposed.path("after"))) throw changed();
        return preview;
    }

    @Override
    public ResponseEntity<?> save(PlatformAuthenticatedSession actor, String kbCode, String revision, Map<String, Object> changes) {
        var preview = preview(actor, kbCode, revision, changes);
        if (!preview.getStatusCode().is2xxSuccessful()) return preview;
        var beforeDispatch = (JsonNode) preview.getBody();
        audit.record(actor, "KNOWLEDGE_SETTINGS_SAVE_STARTED", "KNOWLEDGE_BASE", kbCode,
                Map.of("revision", revision));
        var saved = exchange(actor, "save", Map.of("kbCode", kbCode, "baseRevision", revision, "changes", changes));
        if (!saved.getStatusCode().is2xxSuccessful()) return saved;
        var result = (JsonNode) saved.getBody();
        checkContext(result, kbCode);
        if (!beforeDispatch.path("knowledgeBaseId").equals(result.path("knowledgeBaseId")) || !beforeDispatch.path("resource").equals(result.path("resource")) || !beforeDispatch.path("after").equals(result.path("before"))) {
            // The request may have succeeded; callers must read the original resource rather than blindly retry.
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Knowledge settings save result could not be confirmed");
        }
        return saved;
    }

    private ResponseEntity<?> exchange(PlatformAuthenticatedSession actor, String operation, Map<String, Object> command) {
        byte[] body;
        try { body = json.writeValueAsBytes(command); }
        catch (Exception invalid) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid settings command"); }
        if (body.length > 65536) throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "settings command too large");
        var response = gateway.exchange("POST", KnowledgeBizIndexGateway.CATALOG_INTERNAL_ROOT + "/settings/" + operation,
                consoleTenantId, String.valueOf(actor.user().getId()), body, "application/json");
        if (response == null) return ResponseEntity.status(502).build();
        if (response.status() < 200 || response.status() >= 300) return ResponseEntity.status(response.status()).build();
        try {
            if (response.body() == null || response.body().length > 65536) return ResponseEntity.status(502).build();
            JsonNode envelope = json.readTree(response.body());
            if (!envelope.path("code").isInt() || envelope.path("code").asInt() != 200 || !envelope.path("data").isObject()) return ResponseEntity.status(502).build();
            return ResponseEntity.ok(envelope.get("data"));
        } catch (Exception invalid) { return ResponseEntity.status(502).build(); }
    }
    private void checkContext(JsonNode context, String code) {
        if (context == null || context.path("contractVersion").asInt() != 1 || !code.equals(context.path("kbCode").asText())
                || !context.path("revision").asText().matches("[0-9a-f]{64}") || !completeSettings(context.path("before"))
                || !context.path("knowledgeBaseId").isIntegralNumber() || context.path("knowledgeBaseId").asLong() <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "incomplete Knowledge settings context");
        }
    }
    private boolean completeSettings(JsonNode settings) {
        if (!settings.isObject() || !settings.path("name").isTextual()) return false;
        var keys = new java.util.HashSet<String>(); settings.fieldNames().forEachRemaining(keys::add);
        return SETTINGS_FIELDS.equals(keys);
    }
    private void require(PlatformAuthenticatedSession actor, String permission, JsonNode resource) {
        String scope = resource.path("scope").asText();
        if (!java.util.Set.of("SHARED", "WORKSPACE", "PROJECT").contains(scope) || !resource.path("workspaceId").isTextual()
                || resource.path("workspaceId").asText().isBlank() || scope.equals("PROJECT") && (!resource.path("projectCode").isTextual() || resource.path("projectCode").asText().isBlank())) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "invalid owner scope");
        }
        if (!actor.hasResourcePermission(permission, scope, resource.path("workspaceId").asText(), resource.path("projectCode").asText(null))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Knowledge resource permission required");
        }
    }
    private void requireActor(PlatformAuthenticatedSession actor) {
        if (actor == null || actor.user() == null || actor.user().getId() == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "platform identity required");
    }
    private String code(String value) { if (value == null || !value.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,63}")) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid knowledge base code"); return value; }
    private ResponseStatusException changed() { return new ResponseStatusException(HttpStatus.CONFLICT, "Knowledge settings changed; reread the current revision"); }
}
