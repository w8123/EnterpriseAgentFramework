package com.enterprise.ai.control.mcp.application.platform;

import com.enterprise.ai.control.identity.PlatformAiApprovalService;
import com.enterprise.ai.control.identity.PlatformAiConnectionService.ConnectionIdentity;
import com.enterprise.ai.control.identity.PlatformAuthenticatedSession;
import com.enterprise.ai.control.identity.PlatformPermissions;
import com.enterprise.ai.control.mcp.application.port.McpPlatformModelCatalogPort;
import com.enterprise.ai.control.mcp.application.port.McpPlatformModelWritePort;
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
import java.util.List;
import java.util.Map;

/**
 * Creating, editing or archiving a model instance changes which provider, endpoint and key every bound Agent uses, so
 * an AI request goes to owner review and runs once. Connection secrets are never frozen: each new secret is replaced by
 * a salted fingerprint, and the executing connection presents the same values again. Masked values read back from the
 * catalog keep the stored secret, exactly as the console edit form does.
 */
@Service
@RequiredArgsConstructor
public class McpPlatformModelChangeService {
    public static final String STATE_CHANGED = "MODEL_INSTANCE_CHANGED";
    public enum Operation { CREATE, UPDATE, ARCHIVE }
    private static final TypeReference<LinkedHashMap<String, Object>> MAP = new TypeReference<>() { };
    private static final List<String> FIELDS = List.of("name", "provider", "modelType", "modelName", "protocol", "projectCode",
            "connection", "defaultOptions", "paramsSchema", "remark");
    private static final List<String> STORED = List.of("id", "name", "provider", "modelType", "modelName", "protocol", "projectCode",
            "providerAccountId", "connection", "defaultOptions", "paramsSchema", "status", "remark", "updatedAt");
    private final McpPlatformModelCatalogPort catalog;
    private static final java.util.Set<String> DRAFT_FIELDS = java.util.Set.of("name", "provider", "modelType", "modelName", "protocol",
            "connection", "defaultOptions", "projectCode");
    private final McpPlatformModelWritePort models;
    private final PlatformAiApprovalService approvals;
    private final ObjectMapper json;

    /** Runs a saved instance's connection test; it only records the test result, so it is not gated. */
    public ResponseEntity<?> test(ConnectionIdentity actor, String modelId) {
        try { manageable(actor.session(), modelId); }
        catch (RuntimeException refused) { return refusal(refused); }
        var response = models.change(McpPlatformModelWritePort.Operation.TEST, modelId, Map.of());
        if (response != null && response.getStatusCode().is5xxServerError()) {
            return ResponseEntity.accepted().body(Map.of("status", "UNKNOWN", "modelId", modelId,
                    "message", "连接测试结果未确认，可以稍后读取模型实例的最近测试结果或再次测试。"));
        }
        return response;
    }

    /**
     * Tests a draft connection or lists the provider's models with credentials the AI supplies. A draft that names a
     * saved instance would reuse its stored key against the draft's address, so drafts here never carry an id; a saved
     * instance is tested with {@link #test}.
     */
    public ResponseEntity<?> draft(ConnectionIdentity actor, McpPlatformModelWritePort.DraftOperation operation, Map<String, Object> draft) {
        if (draft == null || draft.containsKey("id") || !DRAFT_FIELDS.containsAll(draft.keySet()) || !(draft.get("connection") instanceof Map<?, ?>)) {
            return ResponseEntity.badRequest().build();
        }
        Object project = draft.get("projectCode");
        if (project != null && !(project instanceof String)) return ResponseEntity.badRequest().build();
        try { requireManage(actor.session(), project == null || ((String) project).isBlank() ? null : ((String) project).trim()); }
        catch (RuntimeException refused) { return refusal(refused); }
        Map<String, Object> body = new java.util.LinkedHashMap<>(draft);
        body.remove("projectCode");
        var response = models.draft(operation, body);
        if (response != null && response.getStatusCode().is5xxServerError()) {
            return ResponseEntity.accepted().body(Map.of("status", "UNKNOWN", "message", "结果未确认，可以稍后再次测试；草稿不会保存。"));
        }
        return response;
    }

    public ResponseEntity<?> request(ConnectionIdentity actor, Operation operation, String modelId, Map<String, Object> fields, String invocationId) {
        Map<String, Object> frozen;
        try { frozen = snapshot(actor, operation, modelId, fields, true); }
        catch (RuntimeException refused) { return refusal(refused); }
        frozen.put("invocationId", invocationId);
        return ResponseEntity.ok(approvals.prepare(actor, PlatformAiApprovalService.MODEL_INSTANCE_CHANGE, subject(modelId), frozen));
    }

    public ResponseEntity<?> execute(ConnectionIdentity actor, String approvalId, Map<String, Object> secrets) {
        var claim = approvals.claim(actor, approvalId, PlatformAiApprovalService.MODEL_INSTANCE_CHANGE);
        var frozen = claim.arguments();
        Operation operation;
        String modelId;
        Map<String, Object> after;
        Map<String, Object> body;
        try {
            operation = Operation.valueOf(String.valueOf(frozen.get("operation")));
            modelId = (String) frozen.get("modelId");
            if (!subject(modelId).equals(claim.subject())) throw changed();
            after = frozen.get("after") == null ? null : json.convertValue(frozen.get("after"), MAP);
            var latest = snapshot(actor, operation, modelId, after, false);
            latest.put("invocationId", frozen.get("invocationId"));
            if (!json.valueToTree(latest).equals(json.valueToTree(frozen))) throw changed();
            body = after == null ? null : withSecrets(after, secrets == null ? Map.of() : secrets);
        } catch (RuntimeException denied) {
            approvals.finish(actor, approvalId, "FAILED", "PLATFORM_APPROVAL_PRE_DISPATCH_FAILED");
            return refusal(denied);
        }
        ResponseEntity<?> response;
        try {
            response = switch (operation) {
                case CREATE -> unwrapOwner(catalog.create(actor.session(), body));
                case UPDATE -> models.change(McpPlatformModelWritePort.Operation.UPDATE, modelId, body);
                case ARCHIVE -> models.change(McpPlatformModelWritePort.Operation.ARCHIVE, modelId, Map.of());
            };
        } catch (RuntimeException failure) {
            var denied = refusal(failure);
            if (denied.getStatusCode().is4xxClientError()) {
                approvals.finish(actor, approvalId, "FAILED", "PLATFORM_APPROVAL_MODEL_REJECTED");
                return denied;
            }
            return unknown(actor, approvalId, modelId);
        }
        if (response != null && response.getStatusCode().is4xxClientError()) {
            approvals.finish(actor, approvalId, "FAILED", "PLATFORM_APPROVAL_MODEL_REJECTED");
            return ResponseEntity.status(response.getStatusCode()).build();
        }
        if (!confirmed(response, operation, modelId, after)) return unknown(actor, approvalId, modelId);
        approvals.finish(actor, approvalId, "SUCCEEDED", null);
        return ResponseEntity.ok(PlatformConnectionSecrets.masked(json.convertValue(response.getBody(), Object.class)));
    }

    private Map<String, Object> snapshot(ConnectionIdentity actor, Operation operation, String modelId, Map<String, Object> input, boolean fresh) {
        if (operation == Operation.CREATE ? modelId != null : !validId(modelId)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("contractVersion", 1); result.put("operation", operation.name()); result.put("modelId", modelId);
        Map<String, Object> before = null;
        if (operation != Operation.CREATE) {
            JsonNode stored = manageable(actor.session(), modelId);
            before = new LinkedHashMap<>();
            for (String field : STORED) before.put(field, PlatformConnectionSecrets.masked(json.convertValue(stored.get(field), Object.class)));
        }
        result.put("before", before);
        result.put("after", switch (operation) {
            case CREATE, UPDATE -> {
                if (input == null || input.isEmpty() || !FIELDS.containsAll(input.keySet())) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
                Map<String, Object> after = new LinkedHashMap<>();
                if (operation == Operation.UPDATE) for (String field : FIELDS) after.put(field, before.get(field));
                after.putAll(input);
                if (!(after.get("name") instanceof String name) || name.isBlank()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
                if (after.get("connection") != null && !(after.get("connection") instanceof Map<?, ?>)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
                if (operation == Operation.UPDATE && !java.util.Objects.equals(after.get("projectCode"), before.get("projectCode"))) {
                    // Moving an instance between projects is a separate decision the console does not offer either.
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
                }
                if (operation == Operation.CREATE) {
                    Object project = after.get("projectCode");
                    if (project != null && !(project instanceof String)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
                    requireManage(actor.session(), (String) project);
                }
                if (fresh) after.put("connection", PlatformConnectionSecrets.fingerprinted(after.get("connection")));
                else PlatformConnectionSecrets.verifyFingerprints(after.get("connection"));
                yield after;
            }
            case ARCHIVE -> {
                if (input != null && !input.isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
                if ("ARCHIVED".equals(before.get("status"))) throw changed();
                yield null;
            }
        });
        try { return json.readValue(json.writeValueAsBytes(result), MAP); }
        catch (Exception invalid) { throw changed(); }
    }

    /** Reads the instance through the scoped console view and requires model:manage on its project. */
    private JsonNode manageable(PlatformAuthenticatedSession session, String modelId) {
        if (!validId(modelId)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        var response = catalog.get(session, modelId);
        if (response == null || !response.getStatusCode().is2xxSuccessful()) {
            throw new ResponseStatusException(response == null ? HttpStatus.SERVICE_UNAVAILABLE : response.getStatusCode());
        }
        JsonNode body = json.valueToTree(response.getBody());
        JsonNode model = body.has("data") ? body.get("data") : body;
        if (model == null || !modelId.equals(model.path("id").asText())) throw changed();
        requireManage(session, model.path("projectCode").isTextual() && !model.path("projectCode").asText().isBlank()
                ? model.path("projectCode").asText() : null);
        return model;
    }

    private static void requireManage(PlatformAuthenticatedSession session, String projectCode) {
        boolean allowed = projectCode == null ? session.hasGlobalPermission(PlatformPermissions.MODEL_MANAGE)
                : session.hasResourcePermission(PlatformPermissions.MODEL_MANAGE, "PROJECT", null, projectCode);
        if (!allowed) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
    }

    private Map<String, Object> withSecrets(Map<String, Object> after, Map<String, Object> secrets) {
        Map<String, Object> body = new LinkedHashMap<>(after);
        body.put("connection", PlatformConnectionSecrets.withSecrets(after.get("connection"), secrets));
        return body;
    }

    private ResponseEntity<?> unwrapOwner(ResponseEntity<?> response) {
        if (response == null || !response.getStatusCode().is2xxSuccessful()) return response;
        JsonNode body = json.valueToTree(response.getBody());
        return ResponseEntity.status(response.getStatusCode()).body(body != null && body.has("data") ? body.get("data") : body);
    }

    private boolean confirmed(ResponseEntity<?> response, Operation operation, String modelId, Map<String, Object> after) {
        if (response == null || !response.getStatusCode().is2xxSuccessful()) return false;
        JsonNode result = json.valueToTree(response.getBody());
        if (result == null || !result.path("id").isTextual()) return false;
        if (operation != Operation.CREATE && !modelId.equals(result.path("id").asText())) return false;
        return operation == Operation.ARCHIVE ? "ARCHIVED".equals(result.path("status").asText())
                : String.valueOf(after.get("name")).equals(result.path("name").asText());
    }

    private static boolean validId(String value) { return value != null && value.matches("[a-zA-Z0-9][a-zA-Z0-9_.-]{0,127}"); }

    private static String subject(String modelId) { return "MODEL_INSTANCE:" + (modelId == null ? "NEW" : modelId); }

    private static ResponseStatusException changed() {
        return new ResponseStatusException(HttpStatus.CONFLICT, "Model instance changed");
    }

    private static ResponseEntity<?> refusal(RuntimeException failure) {
        int status = failure instanceof ResponseStatusException denied ? denied.getStatusCode().value()
                : failure instanceof FeignException remote && remote.status() > 0 ? remote.status()
                : failure instanceof IllegalArgumentException || failure instanceof ClassCastException
                        || failure instanceof NullPointerException ? 409 : 503;
        return status == 409 ? ResponseEntity.status(409).body(Map.of("code", STATE_CHANGED)) : ResponseEntity.status(status).build();
    }

    private ResponseEntity<?> unknown(ConnectionIdentity actor, String approvalId, String modelId) {
        approvals.finish(actor, approvalId, "UNKNOWN", "PLATFORM_APPROVAL_EXECUTION_UNKNOWN");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "UNKNOWN"); body.put("approvalId", approvalId); body.put("modelId", modelId);
        body.put("message", "模型实例修改结果未确认，请查询原审批并重新读取模型列表；不要新建申请直接重试。");
        return ResponseEntity.accepted().body(body);
    }
}
