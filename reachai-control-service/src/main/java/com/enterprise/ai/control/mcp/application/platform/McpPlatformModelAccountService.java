package com.enterprise.ai.control.mcp.application.platform;

import com.enterprise.ai.control.identity.PlatformAiApprovalService;
import com.enterprise.ai.control.identity.PlatformAiConnectionService.ConnectionIdentity;
import com.enterprise.ai.control.identity.PlatformAuthenticatedSession;
import com.enterprise.ai.control.identity.PlatformPermissions;
import com.enterprise.ai.control.mcp.application.port.McpPlatformModelWritePort;
import com.enterprise.ai.control.mcp.application.port.McpPlatformModelWritePort.AccountOperation;
import com.enterprise.ai.control.mcp.application.port.McpPlatformModelWritePort.CatalogOperation;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Provider accounts are platform level: their address and key are the effective connection of every instance that
 * references them, in any project. Reads follow model:read like the console; anything that changes or spends through
 * an account needs global model:manage. Account changes and bulk instance creation go to owner review and run once,
 * with new keys frozen only as salted fingerprints.
 */
@Service
@RequiredArgsConstructor
public class McpPlatformModelAccountService {
    public static final String STATE_CHANGED = "MODEL_PROVIDER_ACCOUNT_CHANGED";
    public static final String IN_USE = "MODEL_PROVIDER_ACCOUNT_IN_USE";
    public enum Operation { CREATE, UPDATE, DELETE, CREATE_INSTANCES }
    private static final TypeReference<LinkedHashMap<String, Object>> MAP = new TypeReference<>() { };
    private static final List<String> STORED = List.of("id", "name", "provider", "connection", "apiKeyConfigured", "status",
            "remark", "instanceCount", "updatedAt");
    private static final Set<String> FIELDS = Set.of("name", "provider", "connection", "remark");
    private static final Set<String> MODEL_TYPES = Set.of("LLM", "EMBEDDING", "RERANKER");
    private static final int MAX_MODELS = 20;
    private final McpPlatformModelWritePort models;
    private final PlatformAiApprovalService approvals;
    private final ObjectMapper json;

    public ResponseEntity<?> list(String provider) {
        if (provider != null && !validId(provider)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        return models.account(AccountOperation.LIST, null, provider == null ? Map.of() : Map.of("provider", provider), null);
    }

    public ResponseEntity<?> get(String accountId) {
        if (!validId(accountId)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        return models.account(AccountOperation.GET, accountId, Map.of(), null);
    }

    /** Lists the provider's current models with the saved key; it changes nothing and can be repeated. */
    public ResponseEntity<?> discoverSaved(ConnectionIdentity actor, String accountId) {
        if (!globalManage(actor.session())) return ResponseEntity.status(403).build();
        if (!validId(accountId)) return ResponseEntity.badRequest().build();
        return models.account(AccountOperation.DISCOVER_SAVED, accountId, Map.of(), null);
    }

    /** Tries an address and key without saving them, like the console's draft discovery. */
    public ResponseEntity<?> discoverDraft(String provider, Map<String, Object> connection) {
        if (!validId(provider) || connection == null) return ResponseEntity.badRequest().build();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("provider", provider); body.put("connection", connection);
        return models.account(AccountOperation.DISCOVER_DRAFT, null, Map.of(), body);
    }

    public ResponseEntity<?> catalogStatus() { return models.catalog(CatalogOperation.STATUS, null); }

    /** Catalog settings only choose the sync switch and analyzer model; the call states the full new value, so it is direct. */
    public ResponseEntity<?> catalogSettings(ConnectionIdentity actor, Boolean autoSyncEnabled, String analyzerModelInstanceId) {
        if (!globalManage(actor.session())) return ResponseEntity.status(403).build();
        if (autoSyncEnabled == null && analyzerModelInstanceId == null) return ResponseEntity.badRequest().build();
        if (analyzerModelInstanceId != null && !analyzerModelInstanceId.isEmpty() && !validId(analyzerModelInstanceId)) {
            return ResponseEntity.badRequest().build();
        }
        Map<String, Object> body = new LinkedHashMap<>();
        if (autoSyncEnabled != null) body.put("autoSyncEnabled", autoSyncEnabled);
        if (analyzerModelInstanceId != null) body.put("analyzerModelInstanceId", analyzerModelInstanceId);
        return models.catalog(CatalogOperation.SETTINGS, body);
    }

    public ResponseEntity<?> catalogSync(ConnectionIdentity actor) {
        if (!globalManage(actor.session())) return ResponseEntity.status(403).build();
        var response = models.catalog(CatalogOperation.SYNC, null);
        if (response != null && response.getStatusCode().is5xxServerError()) {
            return ResponseEntity.accepted().body(Map.of("status", "UNKNOWN",
                    "message", "目录同步是否已启动未确认，请先用 reachai_model_catalog_status 查看最近同步时间，不要直接再次触发。"));
        }
        return response;
    }

    public ResponseEntity<?> request(ConnectionIdentity actor, Operation operation, String accountId, Map<String, Object> fields,
                                     List<Object> modelsToAdd, String invocationId) {
        Map<String, Object> frozen;
        try {
            if (!globalManage(actor.session())) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
            frozen = snapshot(operation, accountId, operation == Operation.CREATE_INSTANCES ? Map.of("models", modelsToAdd) : fields, true);
        } catch (RuntimeException refused) { return refusal(refused); }
        frozen.put("invocationId", invocationId);
        return ResponseEntity.ok(approvals.prepare(actor, PlatformAiApprovalService.MODEL_PROVIDER_ACCOUNT_CHANGE, subject(accountId), frozen));
    }

    public ResponseEntity<?> execute(ConnectionIdentity actor, String approvalId, Map<String, Object> secrets) {
        var claim = approvals.claim(actor, approvalId, PlatformAiApprovalService.MODEL_PROVIDER_ACCOUNT_CHANGE);
        var frozen = claim.arguments();
        Operation operation;
        String accountId;
        Map<String, Object> after;
        Object body;
        try {
            if (!globalManage(actor.session())) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
            operation = Operation.valueOf(String.valueOf(frozen.get("operation")));
            accountId = (String) frozen.get("accountId");
            if (!subject(accountId).equals(claim.subject())) throw changed();
            after = frozen.get("after") == null ? null : json.convertValue(frozen.get("after"), MAP);
            var latest = snapshot(operation, accountId, after, false);
            latest.put("invocationId", frozen.get("invocationId"));
            if (!json.valueToTree(latest).equals(json.valueToTree(frozen))) throw changed();
            Map<String, Object> presented = secrets == null ? Map.of() : secrets;
            if (after != null && after.containsKey("connection")) {
                Map<String, Object> resolved = new LinkedHashMap<>(after);
                resolved.put("connection", PlatformConnectionSecrets.withSecrets(after.get("connection"), presented));
                body = resolved;
            } else {
                if (!presented.isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
                body = after;
            }
        } catch (RuntimeException denied) {
            approvals.finish(actor, approvalId, "FAILED", "PLATFORM_APPROVAL_PRE_DISPATCH_FAILED");
            return refusal(denied);
        }
        ResponseEntity<?> response = switch (operation) {
            case CREATE -> models.account(AccountOperation.CREATE, null, Map.of(), body);
            case UPDATE -> models.account(AccountOperation.UPDATE, accountId, Map.of(), body);
            case DELETE -> models.account(AccountOperation.DELETE, accountId, Map.of(), null);
            case CREATE_INSTANCES -> models.account(AccountOperation.CREATE_INSTANCES, accountId, Map.of(), body);
        };
        if (response != null && response.getStatusCode().is4xxClientError()) {
            approvals.finish(actor, approvalId, "FAILED", "PLATFORM_APPROVAL_MODEL_ACCOUNT_REJECTED");
            return ResponseEntity.status(response.getStatusCode()).build();
        }
        if (!confirmed(response, operation, accountId, after)) {
            approvals.finish(actor, approvalId, "UNKNOWN", "PLATFORM_APPROVAL_EXECUTION_UNKNOWN");
            Map<String, Object> unknown = new LinkedHashMap<>();
            unknown.put("status", "UNKNOWN"); unknown.put("approvalId", approvalId); unknown.put("accountId", accountId);
            unknown.put("message", "提供商账号修改结果未确认，请查询原审批并重新读取账号和模型列表；不要新建申请直接重试。");
            return ResponseEntity.accepted().body(unknown);
        }
        approvals.finish(actor, approvalId, "SUCCEEDED", null);
        return ResponseEntity.ok(PlatformConnectionSecrets.masked(json.convertValue(response.getBody(), Object.class)));
    }

    private Map<String, Object> snapshot(Operation operation, String accountId, Map<String, Object> input, boolean fresh) {
        if (operation == Operation.CREATE ? accountId != null : !validId(accountId)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("contractVersion", 1); result.put("operation", operation.name()); result.put("accountId", accountId);
        Map<String, Object> before = null;
        if (operation != Operation.CREATE) {
            var stored = models.account(AccountOperation.GET, accountId, Map.of(), null);
            if (stored == null || !stored.getStatusCode().is2xxSuccessful()) {
                throw new ResponseStatusException(stored == null ? HttpStatus.SERVICE_UNAVAILABLE : stored.getStatusCode());
            }
            JsonNode account = json.valueToTree(stored.getBody());
            if (account == null || !accountId.equals(account.path("id").asText())) throw changed();
            before = new LinkedHashMap<>();
            for (String field : STORED) before.put(field, PlatformConnectionSecrets.masked(json.convertValue(account.get(field), Object.class)));
        }
        result.put("before", before);
        result.put("after", switch (operation) {
            case CREATE, UPDATE -> {
                if (input == null || input.isEmpty() || !FIELDS.containsAll(input.keySet())) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
                Map<String, Object> after = new LinkedHashMap<>(input);
                if (operation == Operation.CREATE) {
                    if (!(after.get("name") instanceof String name) || name.isBlank() || !(after.get("provider") instanceof String provider)
                            || !validId(provider) || !(after.get("connection") instanceof Map<?, ?>)) {
                        throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
                    }
                } else if (after.containsKey("provider") && !Objects.equals(after.get("provider"), before.get("provider"))) {
                    // The owner refuses a provider change too; refusing here keeps it out of the approval queue.
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
                }
                if (after.containsKey("connection")) {
                    if (!(after.get("connection") instanceof Map<?, ?>)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
                    if (fresh) after.put("connection", PlatformConnectionSecrets.fingerprinted(after.get("connection")));
                    else PlatformConnectionSecrets.verifyFingerprints(after.get("connection"));
                }
                yield after;
            }
            case DELETE -> {
                if (input != null && !input.isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
                if (before.get("instanceCount") instanceof Number count && count.longValue() > 0) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT, IN_USE);
                }
                yield null;
            }
            case CREATE_INSTANCES -> Map.of("models", modelItems(input == null ? null : input.get("models")));
        });
        try { return json.readValue(json.writeValueAsBytes(result), MAP); }
        catch (Exception invalid) { throw changed(); }
    }

    private static List<Map<String, Object>> modelItems(Object raw) {
        if (!(raw instanceof List<?> items) || items.isEmpty() || items.size() > MAX_MODELS) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object item : items) {
            if (!(item instanceof Map<?, ?> model) || !Set.of("modelName", "modelType", "name").containsAll(model.keySet().stream().map(String::valueOf).toList())
                    || !(model.get("modelName") instanceof String name) || name.isBlank() || name.length() > 200
                    || !(model.get("modelType") instanceof String type) || !MODEL_TYPES.contains(type)
                    || model.get("name") != null && !(model.get("name") instanceof String)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
            }
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("modelName", name.trim()); entry.put("modelType", type); entry.put("name", model.get("name"));
            result.add(entry);
        }
        return result;
    }

    private boolean confirmed(ResponseEntity<?> response, Operation operation, String accountId, Map<String, Object> after) {
        if (response == null || !response.getStatusCode().is2xxSuccessful()) return false;
        if (operation == Operation.DELETE) return true;
        JsonNode result = json.valueToTree(response.getBody());
        if (result == null) return false;
        return switch (operation) {
            case CREATE -> result.path("id").isTextual() && String.valueOf(after.get("name")).trim().equals(result.path("name").asText());
            case UPDATE -> accountId.equals(result.path("id").asText());
            case CREATE_INSTANCES -> result.path("created").isArray();
            case DELETE -> true;
        };
    }

    private static boolean globalManage(PlatformAuthenticatedSession session) {
        return session != null && session.hasGlobalPermission(PlatformPermissions.MODEL_MANAGE);
    }

    private static boolean validId(String value) { return value != null && value.matches("[a-zA-Z0-9][a-zA-Z0-9_.-]{0,127}"); }

    private static String subject(String accountId) { return "MODEL_PROVIDER_ACCOUNT:" + (accountId == null ? "NEW" : accountId); }

    private static ResponseStatusException changed() {
        return new ResponseStatusException(HttpStatus.CONFLICT, "Provider account changed");
    }

    private static ResponseEntity<?> refusal(RuntimeException failure) {
        int status = failure instanceof ResponseStatusException denied ? denied.getStatusCode().value()
                : failure instanceof IllegalArgumentException || failure instanceof ClassCastException
                        || failure instanceof NullPointerException ? 409 : 503;
        if (status == 409) {
            boolean inUse = failure instanceof ResponseStatusException denied && IN_USE.equals(denied.getReason());
            return ResponseEntity.status(409).body(Map.of("code", inUse ? IN_USE : STATE_CHANGED));
        }
        return ResponseEntity.status(status).build();
    }
}
