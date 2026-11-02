package com.enterprise.ai.control.mcp.application.platform;

import com.enterprise.ai.control.identity.PlatformAiApprovalService;
import com.enterprise.ai.control.identity.PlatformAiConnectionService.ConnectionIdentity;
import com.enterprise.ai.control.mcp.application.port.McpPlatformPublicationPort;
import com.enterprise.ai.control.mcp.application.port.McpPlatformPublicationPort.Change;
import com.enterprise.ai.control.mcp.application.port.McpPlatformPublicationPort.Read;
import com.enterprise.ai.control.mcp.domain.McpDomainException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Publishing, suspending, resuming, archiving or rolling back an MCP publication changes which tools external clients
 * can call; changing employee access or a client's roles and scope changes who can call them. Platform AI requests
 * such a change, the owner reviews the frozen publication, and the original connection runs it once after the
 * publication is read again and found unchanged.
 */
@Service
@RequiredArgsConstructor
public class McpPlatformPublicationService {
    public static final String STATE_CHANGED = "MCP_PUBLICATION_CHANGED";
    private static final TypeReference<LinkedHashMap<String, Object>> MAP = new TypeReference<>() { };
    private static final Set<String> EMPLOYEE_FIELDS = Set.of("projectId", "projectCode", "displayName", "enabled", "writeApprovalRequired");
    private static final Set<String> CLIENT_FIELDS = Set.of("roles", "toolScope", "enabled", "expiresAt");
    private final McpPlatformPublicationPort publications;
    private final PlatformAiApprovalService approvals;
    private final ObjectMapper json;

    public ResponseEntity<?> request(ConnectionIdentity actor, Change operation, long publicationId, Long targetId,
                                     Map<String, Object> input, String invocationId) {
        Map<String, Object> body = input == null ? Map.of() : input;
        if (!publications.admits(actor.session(), operation, body)) return ResponseEntity.status(403).build();
        Map<String, Object> frozen;
        try { frozen = snapshot(actor, operation, publicationId, targetId, body); }
        catch (McpDomainException rejected) { return ResponseEntity.status(409).body(Map.of("code", rejected.code())); }
        catch (RuntimeException refused) { return refusal(refused); }
        frozen.put("invocationId", invocationId);
        return ResponseEntity.ok(approvals.prepare(actor, PlatformAiApprovalService.MCP_PUBLICATION_CHANGE, subject(publicationId), frozen));
    }

    public ResponseEntity<?> execute(ConnectionIdentity actor, String approvalId) {
        var claim = approvals.claim(actor, approvalId, PlatformAiApprovalService.MCP_PUBLICATION_CHANGE);
        var frozen = claim.arguments();
        Change operation;
        long publicationId;
        Long targetId;
        Map<String, Object> after;
        try {
            operation = Change.valueOf(String.valueOf(frozen.get("operation")));
            publicationId = ((Number) frozen.get("publicationId")).longValue();
            targetId = frozen.get("targetId") == null ? null : ((Number) frozen.get("targetId")).longValue();
            if (!subject(publicationId).equals(claim.subject())) throw changed();
            after = frozen.get("after") == null ? Map.of() : json.convertValue(frozen.get("after"), MAP);
            if (!publications.admits(actor.session(), operation, after)) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
            var latest = snapshot(actor, operation, publicationId, targetId, after);
            latest.put("invocationId", frozen.get("invocationId"));
            if (!json.valueToTree(latest).equals(json.valueToTree(frozen))) throw changed();
        } catch (RuntimeException denied) {
            approvals.finish(actor, approvalId, "FAILED", "PLATFORM_APPROVAL_PRE_DISPATCH_FAILED");
            return denied instanceof McpDomainException rejected
                    ? ResponseEntity.status(409).body(Map.of("code", rejected.code())) : refusal(denied);
        }
        ResponseEntity<?> response;
        try {
            response = publications.change(actor.session(), operation, publicationId, targetId, after);
        } catch (McpDomainException rejected) {
            approvals.finish(actor, approvalId, "FAILED", "PLATFORM_APPROVAL_MCP_PUBLICATION_REJECTED");
            return ResponseEntity.status(409).body(Map.of("code", rejected.code()));
        } catch (ResponseStatusException rejected) {
            if (rejected.getStatusCode().is4xxClientError()) {
                approvals.finish(actor, approvalId, "FAILED", "PLATFORM_APPROVAL_MCP_PUBLICATION_REJECTED");
                return ResponseEntity.status(rejected.getStatusCode()).build();
            }
            return unknown(actor, approvalId, publicationId);
        } catch (RuntimeException uncertain) {
            return unknown(actor, approvalId, publicationId);
        }
        if (response == null || !response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
            return unknown(actor, approvalId, publicationId);
        }
        approvals.finish(actor, approvalId, "SUCCEEDED", null);
        return response;
    }

    private Map<String, Object> snapshot(ConnectionIdentity actor, Change operation, long publicationId, Long targetId, Map<String, Object> input) {
        if (publicationId <= 0) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        JsonNode detail = read(actor, Read.DETAIL, publicationId);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("contractVersion", 1); result.put("operation", operation.name());
        result.put("publicationId", publicationId); result.put("targetId", targetId);
        result.put("publication", detail.get("publication"));
        // Items carry their own update times, so any draft edit after the request shows up as a change.
        result.put("items", detail.get("items"));
        result.put("revisions", detail.get("revisions"));
        Map<String, Object> after = new LinkedHashMap<>();
        switch (operation) {
            case PUBLISH -> {
                if (!Set.of("irreversibleAcknowledged").containsAll(input.keySet())) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
                after.put("irreversibleAcknowledged", Boolean.TRUE.equals(input.get("irreversibleAcknowledged")));
                result.put("precheck", read(actor, Read.PRECHECK, publicationId));
            }
            case SUSPEND, RESUME, ARCHIVE -> {
                if (!input.isEmpty() || targetId != null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
            }
            case ROLLBACK -> {
                if (!input.isEmpty() || targetId == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
                boolean known = false;
                for (JsonNode revision : detail.path("revisions")) known |= revision.path("id").asLong() == targetId;
                if (!known) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
            }
            case EMPLOYEE_ACCESS -> {
                if (input.isEmpty() || !EMPLOYEE_FIELDS.containsAll(input.keySet()) || targetId != null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
                JsonNode current = read(actor, Read.EMPLOYEE_ACCESS, publicationId);
                result.put("employeeAccess", current);
                // Like the console form, the request states the whole configuration; unspecified fields keep their value.
                for (String field : List.of("projectId", "projectCode", "displayName", "enabled", "writeApprovalRequired")) {
                    after.put(field, input.containsKey(field) ? input.get(field) : json.convertValue(current.get(field), Object.class));
                }
            }
            case CLIENT_UPDATE, CLIENT_REVOKE -> {
                if (targetId == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
                JsonNode client = null;
                for (JsonNode candidate : read(actor, Read.CLIENTS, publicationId)) {
                    if (candidate.path("id").asLong() == targetId) client = candidate;
                }
                if (client == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
                Map<String, Object> view = json.convertValue(client, MAP);
                view.remove("lastUsedAt");
                result.put("client", view);
                if (operation == Change.CLIENT_REVOKE) {
                    if (!input.isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
                    if ("REVOKED".equals(view.get("state"))) throw changed();
                } else {
                    if (input.isEmpty() || !CLIENT_FIELDS.containsAll(input.keySet())) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
                    for (String field : List.of("roles", "toolScope")) {
                        Object value = input.get(field);
                        if (value != null && !(value instanceof List<?> list && list.stream().allMatch(String.class::isInstance))) {
                            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
                        }
                    }
                    if (input.get("enabled") != null && !(input.get("enabled") instanceof Boolean)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
                    if (input.get("expiresAt") != null) java.time.LocalDateTime.parse(String.valueOf(input.get("expiresAt")));
                    after.putAll(input);
                }
            }
        }
        result.put("after", after.isEmpty() ? null : after);
        try { return json.readValue(json.writeValueAsBytes(result), MAP); }
        catch (Exception invalid) { throw changed(); }
    }

    private JsonNode read(ConnectionIdentity actor, Read operation, long publicationId) {
        var response = publications.read(actor.session(), operation, publicationId, Map.of());
        if (response == null || !response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
            throw new ResponseStatusException(response == null ? HttpStatus.SERVICE_UNAVAILABLE : response.getStatusCode());
        }
        return json.valueToTree(response.getBody());
    }

    private static String subject(long publicationId) { return "MCP_PUBLICATION:" + publicationId; }

    private static ResponseStatusException changed() {
        return new ResponseStatusException(HttpStatus.CONFLICT, "MCP publication changed");
    }

    private static ResponseEntity<?> refusal(RuntimeException failure) {
        int status = failure instanceof ResponseStatusException denied ? denied.getStatusCode().value()
                : failure instanceof IllegalArgumentException || failure instanceof ClassCastException
                        || failure instanceof NullPointerException || failure instanceof java.time.format.DateTimeParseException ? 400 : 503;
        return status == 409 ? ResponseEntity.status(409).body(Map.of("code", STATE_CHANGED)) : ResponseEntity.status(status).build();
    }

    private ResponseEntity<?> unknown(ConnectionIdentity actor, String approvalId, long publicationId) {
        approvals.finish(actor, approvalId, "UNKNOWN", "PLATFORM_APPROVAL_EXECUTION_UNKNOWN");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "UNKNOWN"); body.put("approvalId", approvalId); body.put("publicationId", publicationId);
        body.put("message", "MCP 发布修改结果未确认，请查询原审批并重新读取发布详情；不要新建申请直接重试。");
        return ResponseEntity.accepted().body(body);
    }
}
