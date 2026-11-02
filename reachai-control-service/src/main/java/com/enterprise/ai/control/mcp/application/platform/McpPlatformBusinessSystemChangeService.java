package com.enterprise.ai.control.mcp.application.platform;

import com.enterprise.ai.control.identity.PlatformAiApprovalService;
import com.enterprise.ai.control.identity.PlatformAiConnectionService.ConnectionIdentity;
import com.enterprise.ai.control.mcp.application.port.McpPlatformBusinessSystemReadPort;
import com.enterprise.ai.control.mcp.application.port.McpPlatformBusinessSystemReadPort.Change;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Creating, editing, re-scoping, deleting a business system or replacing its registry credential changes what every
 * Agent can reach, so an AI request freezes the stored configuration and the exact change for the owner's review and
 * runs once. A registry secret is never frozen: the approval keeps only a salted fingerprint, and the executing
 * connection must present the same secret again.
 */
@Service
@RequiredArgsConstructor
public class McpPlatformBusinessSystemChangeService {
    public static final String STATE_CHANGED = "BUSINESS_SYSTEM_CHANGED";
    private static final TypeReference<LinkedHashMap<String, Object>> MAP = new TypeReference<>() { };
    private static final Map<String, Integer> FIELDS = fields();
    private static final List<String> STORED = List.of("name", "projectCode", "projectKind", "environment", "owner", "visibility",
            "baseUrl", "contextPath", "specFile", "openapiDocumentUploaded", "authType", "registryCredentialConfigured", "scanSettings");
    private static final int MAX_SCAN_SETTINGS_BYTES = 16 * 1024;
    private static final SecureRandom RANDOM = new SecureRandom();
    private final McpPlatformBusinessSystemReadPort systems;
    private final PlatformAiApprovalService approvals;
    private final ObjectMapper json;

    public ResponseEntity<?> request(ConnectionIdentity actor, Change change, Long projectId, Map<String, Object> input, String invocationId) {
        if (change == Change.REGISTRY_CREDENTIAL) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        return prepare(actor, change, projectId, input, invocationId);
    }

    public ResponseEntity<?> requestCredential(ConnectionIdentity actor, long projectId, String appKey, String appSecret, String invocationId) {
        if (appSecret == null || appSecret.isBlank() || appSecret.length() > 512) return ResponseEntity.badRequest().build();
        byte[] salt = new byte[16];
        RANDOM.nextBytes(salt);
        String saltHex = HexFormat.of().formatHex(salt);
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("appKey", appKey); input.put("appSecretSalt", saltHex); input.put("appSecretSha256", fingerprint(saltHex, appSecret));
        return prepare(actor, Change.REGISTRY_CREDENTIAL, projectId, input, invocationId);
    }

    public ResponseEntity<?> execute(ConnectionIdentity actor, String approvalId, String appSecret) {
        var claim = approvals.claim(actor, approvalId, PlatformAiApprovalService.BUSINESS_SYSTEM_CHANGE);
        var frozen = claim.arguments();
        Change change;
        Long projectId;
        Map<String, Object> after;
        try {
            change = Change.valueOf(String.valueOf(frozen.get("operation")));
            projectId = frozen.get("projectId") == null ? null : ((Number) frozen.get("projectId")).longValue();
            if (!subject(projectId).equals(claim.subject())) throw changed();
            after = frozen.get("after") == null ? null : json.convertValue(frozen.get("after"), MAP);
            if (change == Change.REGISTRY_CREDENTIAL) {
                if (appSecret == null || !MessageDigest.isEqual(
                        fingerprint(String.valueOf(after.get("appSecretSalt")), appSecret).getBytes(StandardCharsets.US_ASCII),
                        String.valueOf(after.get("appSecretSha256")).getBytes(StandardCharsets.US_ASCII))) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "registry secret does not match the approved fingerprint");
                }
            } else if (appSecret != null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
            }
            var latest = snapshot(actor, change, projectId, after);
            latest.put("invocationId", frozen.get("invocationId"));
            if (!json.valueToTree(latest).equals(json.valueToTree(frozen))) throw changed();
        } catch (RuntimeException denied) {
            approvals.finish(actor, approvalId, "FAILED", "PLATFORM_APPROVAL_PRE_DISPATCH_FAILED");
            return refusal(denied);
        }
        Object body = switch (change) {
            case CREATE, UPDATE -> after;
            case SCAN_SETTINGS -> after.get("scanSettings");
            case REGISTRY_CREDENTIAL -> Map.of("appKey", after.get("appKey"), "appSecret", appSecret);
            case DELETE -> null;
        };
        ResponseEntity<?> response;
        try { response = systems.change(actor.session(), change, projectId, body); }
        catch (RuntimeException failure) {
            var denied = refusal(failure);
            if (denied.getStatusCode().is4xxClientError()) {
                approvals.finish(actor, approvalId, "FAILED", "PLATFORM_APPROVAL_BUSINESS_SYSTEM_REJECTED");
                return denied;
            }
            return unknown(actor, approvalId, projectId);
        }
        if (response != null && response.getStatusCode().is4xxClientError()) {
            approvals.finish(actor, approvalId, "FAILED", "PLATFORM_APPROVAL_BUSINESS_SYSTEM_REJECTED");
            return response;
        }
        if (!confirmed(response, change, projectId, after)) return unknown(actor, approvalId, projectId);
        approvals.finish(actor, approvalId, "SUCCEEDED", null);
        return change == Change.DELETE ? ResponseEntity.ok(Map.of("deleted", true, "projectId", projectId)) : response;
    }

    private ResponseEntity<?> prepare(ConnectionIdentity actor, Change change, Long projectId, Map<String, Object> input, String invocationId) {
        Map<String, Object> frozen;
        try {
            if (!systems.admits(actor.session(), change, projectId)) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
            frozen = snapshot(actor, change, projectId, input);
        } catch (RuntimeException refused) { return refusal(refused); }
        frozen.put("invocationId", invocationId);
        return ResponseEntity.ok(approvals.prepare(actor, PlatformAiApprovalService.BUSINESS_SYSTEM_CHANGE, subject(projectId), frozen));
    }

    private Map<String, Object> snapshot(ConnectionIdentity actor, Change change, Long projectId, Map<String, Object> input) {
        if (change == Change.CREATE ? projectId != null : projectId == null || projectId < 1) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("contractVersion", 1); result.put("operation", change.name()); result.put("projectId", projectId);
        Map<String, Object> before = null;
        if (change != Change.CREATE) {
            var response = systems.read(actor.session(), McpPlatformBusinessSystemReadPort.Operation.PROJECT, projectId, null, Map.of());
            if (response == null || !response.getStatusCode().is2xxSuccessful()) {
                throw new ResponseStatusException(response == null ? HttpStatus.SERVICE_UNAVAILABLE : response.getStatusCode());
            }
            JsonNode stored = json.valueToTree(response.getBody());
            if (stored == null || stored.path("id").asLong() != projectId) throw changed();
            before = new LinkedHashMap<>();
            for (String field : STORED) before.put(field, json.convertValue(stored.get(field), Object.class));
        }
        result.put("before", before);
        result.put("after", switch (change) {
            case CREATE -> configuration(input);
            case UPDATE -> {
                // Fields the caller leaves out keep their stored value instead of being cleared by the full PUT.
                if (input == null || input.isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
                Map<String, Object> merged = new LinkedHashMap<>();
                for (String field : FIELDS.keySet()) merged.put(field, before.get(field));
                merged.putAll(input);
                yield configuration(merged);
            }
            case SCAN_SETTINGS -> {
                if (input == null || input.size() != 1 || !(input.get("scanSettings") instanceof Map<?, ?> settings)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
                try {
                    if (json.writeValueAsBytes(settings).length > MAX_SCAN_SETTINGS_BYTES) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
                } catch (com.fasterxml.jackson.core.JsonProcessingException invalid) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST); }
                if (json.valueToTree(settings).equals(json.valueToTree(before.get("scanSettings")))) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
                yield Map.of("scanSettings", settings);
            }
            case REGISTRY_CREDENTIAL -> {
                if (input == null || !input.keySet().equals(Set.of("appKey", "appSecretSalt", "appSecretSha256"))
                        || !String.valueOf(input.get("appSecretSalt")).matches("[0-9a-f]{32}")
                        || !String.valueOf(input.get("appSecretSha256")).matches("[0-9a-f]{64}")) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
                }
                Map<String, Object> credential = new LinkedHashMap<>();
                credential.put("appKey", required(input.get("appKey"), 128));
                credential.put("appSecretSalt", input.get("appSecretSalt"));
                credential.put("appSecretSha256", input.get("appSecretSha256"));
                yield credential;
            }
            case DELETE -> {
                if (input != null && !input.isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
                yield null;
            }
        });
        try { return json.readValue(json.writeValueAsBytes(result), MAP); }
        catch (Exception invalid) { throw changed(); }
    }

    private static Map<String, Object> configuration(Map<String, Object> input) {
        if (input == null || !FIELDS.keySet().containsAll(input.keySet())) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        Map<String, Object> result = new LinkedHashMap<>();
        FIELDS.forEach((field, max) -> result.put(field, "name".equals(field) ? required(input.get(field), max) : optional(input.get(field), max)));
        return result;
    }

    private boolean confirmed(ResponseEntity<?> response, Change change, Long projectId, Map<String, Object> after) {
        if (response == null || !response.getStatusCode().is2xxSuccessful()) return false;
        if (change == Change.DELETE) return response.getStatusCode().value() == 204 || response.getStatusCode().value() == 200;
        JsonNode result = json.valueToTree(response.getBody());
        if (result == null || !result.path("id").canConvertToLong() || result.path("id").asLong() < 1
                || change != Change.CREATE && result.path("id").asLong() != projectId) return false;
        return switch (change) {
            case CREATE, UPDATE -> String.valueOf(after.get("name")).equals(result.path("name").asText());
            case REGISTRY_CREDENTIAL -> result.path("registryCredentialConfigured").asBoolean(false);
            default -> true;
        };
    }

    private static String fingerprint(String salt, String secret) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            digest.update(salt.getBytes(StandardCharsets.US_ASCII));
            return HexFormat.of().formatHex(digest.digest(secret.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    private static Map<String, Integer> fields() {
        Map<String, Integer> fields = new LinkedHashMap<>();
        fields.put("name", 128); fields.put("projectCode", 64); fields.put("projectKind", 32); fields.put("environment", 32);
        fields.put("owner", 128); fields.put("visibility", 32); fields.put("baseUrl", 512); fields.put("contextPath", 256);
        return fields;
    }

    private static String required(Object value, int max) {
        if (!(value instanceof String text) || text.isBlank() || text.trim().length() > max) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        return text.trim();
    }

    private static String optional(Object value, int max) {
        if (value == null || value instanceof String text && text.isBlank()) return null;
        return required(value, max);
    }

    private static String subject(Long projectId) { return "BUSINESS_SYSTEM:" + (projectId == null ? "NEW" : projectId); }

    private static ResponseStatusException changed() {
        return new ResponseStatusException(HttpStatus.CONFLICT, "Business system changed");
    }

    private static ResponseEntity<?> refusal(RuntimeException failure) {
        int status = failure instanceof ResponseStatusException denied ? denied.getStatusCode().value()
                : failure instanceof IllegalArgumentException || failure instanceof ClassCastException
                        || failure instanceof NullPointerException ? 409 : 503;
        return status == 409 ? ResponseEntity.status(409).body(Map.of("code", STATE_CHANGED)) : ResponseEntity.status(status).build();
    }

    private ResponseEntity<?> unknown(ConnectionIdentity actor, String approvalId, Long projectId) {
        approvals.finish(actor, approvalId, "UNKNOWN", "PLATFORM_APPROVAL_EXECUTION_UNKNOWN");
        var body = new LinkedHashMap<String, Object>();
        body.put("status", "UNKNOWN"); body.put("approvalId", approvalId); body.put("projectId", projectId);
        body.put("message", "业务系统修改结果未确认，请查询原审批并重新读取业务系统；不要新建申请直接重试。");
        return ResponseEntity.accepted().body(body);
    }
}
