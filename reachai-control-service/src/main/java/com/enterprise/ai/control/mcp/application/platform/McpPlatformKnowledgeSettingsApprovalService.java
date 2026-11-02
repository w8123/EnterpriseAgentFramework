package com.enterprise.ai.control.mcp.application.platform;

import com.enterprise.ai.control.identity.PlatformAiApprovalService;
import com.enterprise.ai.control.identity.PlatformAiConnectionService.ConnectionIdentity;
import com.enterprise.ai.control.mcp.application.port.McpPlatformKnowledgeSettingsPort;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import feign.FeignException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** A reviewed settings edit is a frozen patch against one resource revision, consumed before dispatch. */
@Service
@RequiredArgsConstructor
public class McpPlatformKnowledgeSettingsApprovalService {
    public static final Set<String> FIELDS = Set.of("name", "description", "embeddingModelInstanceId", "llmModelInstanceId", "rerankModelInstanceId",
            "accessMode", "chunkSize", "chunkOverlap", "splitType", "searchMode", "topK", "similarityThreshold", "rerankEnabled", "vectorWeight", "keywordWeight");
    private static final TypeReference<LinkedHashMap<String, Object>> MAP = new TypeReference<>() { };
    private final McpPlatformKnowledgeSettingsPort settings;
    private final PlatformAiApprovalService approvals;
    private final ObjectMapper json;

    public ResponseEntity<?> request(ConnectionIdentity actor, String code, String revision, Map<String, Object> changes, String invocationId) {
        var response = settings.preview(actor.session(), code, revision, changes);
        if (response == null) return ResponseEntity.status(503).build();
        if (!response.getStatusCode().is2xxSuccessful()) return response;
        var frozen = snapshot(response.getBody(), code, revision, changes);
        frozen.put("invocationId", invocationId);
        return ResponseEntity.ok(approvals.prepare(actor, PlatformAiApprovalService.KNOWLEDGE_SETTINGS_WRITE, "KNOWLEDGE:" + code, frozen));
    }

    public ResponseEntity<?> execute(ConnectionIdentity actor, String approvalId) {
        var claim = approvals.claim(actor, approvalId, PlatformAiApprovalService.KNOWLEDGE_SETTINGS_WRITE);
        var frozen = claim.arguments();
        String code, revision;
        Map<String, Object> changes;
        try {
            code = text(frozen.get("kbCode")); revision = text(frozen.get("baseRevision"));
            if (!claim.subject().equals("KNOWLEDGE:" + code)) throw changed();
            changes = json.convertValue(frozen.get("changes"), MAP);
            var response = settings.preview(actor.session(), code, revision, changes);
            if (response == null || !response.getStatusCode().is2xxSuccessful()) {
                throw new ResponseStatusException(response == null ? HttpStatus.SERVICE_UNAVAILABLE : response.getStatusCode());
            }
            var latest = snapshot(response.getBody(), code, revision, changes);
            latest.put("invocationId", frozen.get("invocationId"));
            if (!json.valueToTree(latest).equals(json.valueToTree(frozen))) throw changed();
        } catch (RuntimeException denied) {
            approvals.finish(actor, approvalId, "FAILED", "PLATFORM_APPROVAL_PRE_DISPATCH_FAILED");
            return refusal(denied);
        }
        ResponseEntity<?> response;
        try { response = settings.save(actor.session(), code, revision, changes); }
        catch (RuntimeException failure) {
            var refusal = refusal(failure);
            if (refusal.getStatusCode().is4xxClientError()) {
                approvals.finish(actor, approvalId, "FAILED", "PLATFORM_APPROVAL_SETTINGS_REJECTED");
                return refusal;
            }
            return unknown(actor, approvalId, code);
        }
        if (response != null && response.getStatusCode().is4xxClientError()) {
            approvals.finish(actor, approvalId, "FAILED", "PLATFORM_APPROVAL_SETTINGS_REJECTED"); return response;
        }
        if (!confirmed(response, frozen)) return unknown(actor, approvalId, code);
        approvals.finish(actor, approvalId, "SUCCEEDED", null);
        return response;
    }

    private Map<String, Object> snapshot(Object body, String code, String revision, Map<String, Object> changes) {
        JsonNode context = json.valueToTree(body);
        if (context == null || !code.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,63}") || revision == null || !revision.matches("[a-f0-9]{64}")
                || !context.path("contractVersion").isIntegralNumber() || context.path("contractVersion").asInt() != 1 || !code.equals(context.path("kbCode").asText())
                || !revision.equals(context.path("revision").asText()) || !positiveId(context.path("knowledgeBaseId"))
                || !context.has("dimension") || !complete(context.path("before")) || !complete(context.path("after")) || !validResource(context.path("resource"))
                || changes == null || changes.isEmpty() || !FIELDS.containsAll(changes.keySet())) throw changed();
        var normalizedChanges = json.createObjectNode();
        for (String field : changes.keySet()) normalizedChanges.set(field, context.path("after").get(field));
        for (String field : FIELDS) {
            if (!changes.containsKey(field) && !context.path("before").get(field).equals(context.path("after").get(field))) throw changed();
        }
        var result = new LinkedHashMap<String, Object>();
        result.put("contractVersion", 1); result.put("kbCode", code); result.put("knowledgeBaseId", context.get("knowledgeBaseId"));
        result.put("baseRevision", revision); result.put("resource", context.get("resource")); result.put("dimension", context.get("dimension"));
        result.put("before", context.get("before")); result.put("after", context.get("after")); result.put("changes", normalizedChanges);
        // Round-trip once so Float/Double and Integer/Long representations compare the same after encrypted storage.
        try { return json.readValue(json.writeValueAsBytes(result), MAP); }
        catch (Exception invalid) { throw changed(); }
    }

    private boolean confirmed(ResponseEntity<?> response, Map<String, Object> frozen) {
        if (response == null || !response.getStatusCode().is2xxSuccessful()) return false;
        try {
            JsonNode result = json.readTree(json.writeValueAsBytes(response.getBody())), expected = json.valueToTree(frozen);
            return result != null && result.path("contractVersion").isIntegralNumber() && result.path("contractVersion").asInt() == 1
                    && expected.path("kbCode").equals(result.path("kbCode")) && positiveId(result.path("knowledgeBaseId"))
                    && expected.path("knowledgeBaseId").asLong() == result.path("knowledgeBaseId").asLong()
                    && expected.path("dimension").equals(result.path("dimension")) && expected.path("resource").equals(result.path("resource"))
                    && complete(result.path("before")) && expected.path("after").equals(result.path("before"))
                    && result.path("revision").asText().matches("[a-f0-9]{64}");
        } catch (Exception invalid) { return false; }
    }
    private boolean complete(JsonNode snapshot) {
        if (!snapshot.isObject() || !snapshot.path("name").isTextual()) return false;
        var fields = new java.util.HashSet<String>(); snapshot.fieldNames().forEachRemaining(fields::add);
        if (!FIELDS.equals(fields)) return false;
        for (JsonNode value : snapshot) if (!value.isValueNode() || value.isNumber() && !Double.isFinite(value.asDouble())) return false;
        return true;
    }
    private boolean validResource(JsonNode resource) {
        String scope = resource.path("scope").asText();
        return resource.isObject() && Set.of("SHARED", "WORKSPACE", "PROJECT").contains(scope)
                && resource.path("workspaceId").isTextual() && !resource.path("workspaceId").asText().isBlank()
                && (!scope.equals("PROJECT") || resource.path("projectCode").isTextual() && !resource.path("projectCode").asText().isBlank());
    }
    private boolean positiveId(JsonNode value) { return value.isIntegralNumber() && value.canConvertToLong() && value.longValue() > 0; }
    private String text(Object value) { if (value instanceof String text && !text.isBlank()) return text; throw changed(); }
    private ResponseStatusException changed() { return new ResponseStatusException(HttpStatus.CONFLICT, "Knowledge settings approval changed"); }
    private ResponseEntity<?> refusal(RuntimeException failure) {
        int status = failure instanceof ResponseStatusException denied ? denied.getStatusCode().value()
                : failure instanceof FeignException remote && remote.status() > 0 ? remote.status() : 503;
        return ResponseEntity.status(status).build();
    }
    private ResponseEntity<?> unknown(ConnectionIdentity actor, String id, String code) {
        approvals.finish(actor, id, "UNKNOWN", "PLATFORM_APPROVAL_EXECUTION_UNKNOWN");
        return ResponseEntity.accepted().body(Map.of("status", "UNKNOWN", "approvalId", id, "kbCode", code,
                "message", "设置保存结果未确认，请查询原审批并读取知识库当前设置；不要新建请求直接重试。"));
    }
}
