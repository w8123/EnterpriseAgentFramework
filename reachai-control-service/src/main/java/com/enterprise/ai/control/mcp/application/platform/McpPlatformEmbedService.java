package com.enterprise.ai.control.mcp.application.platform;

import com.enterprise.ai.control.identity.PlatformAiApprovalService;
import com.enterprise.ai.control.identity.PlatformAiConnectionService.ConnectionIdentity;
import com.enterprise.ai.control.mcp.application.port.McpPlatformEmbedPort;
import com.enterprise.ai.control.mcp.application.port.McpPlatformEmbedPort.Change;
import com.enterprise.ai.control.mcp.application.port.McpPlatformEmbedPort.Read;
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
import java.util.Set;

/**
 * An embed credential policy decides which sites and Agents may open the embedded chat, and deleting a registered page
 * also deletes its sessions and chat history, so both go to owner review and run once against what was reviewed.
 */
@Service
@RequiredArgsConstructor
public class McpPlatformEmbedService {
    public static final String STATE_CHANGED = "EMBED_TARGET_CHANGED";
    private static final TypeReference<LinkedHashMap<String, Object>> MAP = new TypeReference<>() { };
    private static final TypeReference<List<String>> STRINGS = new TypeReference<>() { };
    private static final Set<String> POLICY_FIELDS = Set.of("allowedOrigins", "allowedAgentIds", "tokenTtlSeconds", "status");
    // Heartbeats move these on their own; what the owner approves is which page and actions disappear.
    private static final List<String> PAGE_FROZEN = List.of("id", "projectCode", "appId", "pageKey", "name", "routePattern", "origin");
    private final McpPlatformEmbedPort embed;
    private final PlatformAiApprovalService approvals;
    private final ObjectMapper json;

    public ResponseEntity<?> read(ConnectionIdentity actor, Read read, Map<String, Object> query) {
        return embed.read(actor.session(), read, query);
    }

    public ResponseEntity<?> request(ConnectionIdentity actor, Change change, long id, Map<String, Object> fields, String invocationId) {
        Map<String, Object> frozen;
        try { frozen = snapshot(actor, change, id, fields); }
        catch (RuntimeException refused) { return refusal(refused); }
        frozen.put("invocationId", invocationId);
        return ResponseEntity.ok(approvals.prepare(actor, PlatformAiApprovalService.EMBED_CHANGE, subject(change, id), frozen));
    }

    public ResponseEntity<?> execute(ConnectionIdentity actor, String approvalId) {
        var claim = approvals.claim(actor, approvalId, PlatformAiApprovalService.EMBED_CHANGE);
        var frozen = claim.arguments();
        Change change;
        long id;
        Map<String, Object> latest;
        try {
            change = Change.valueOf(String.valueOf(frozen.get("operation")));
            id = ((Number) frozen.get("targetId")).longValue();
            if (!subject(change, id).equals(claim.subject())) throw changed();
            Map<String, Object> request = frozen.get("request") == null ? null : json.convertValue(frozen.get("request"), MAP);
            latest = snapshot(actor, change, id, request);
            latest.put("invocationId", frozen.get("invocationId"));
            if (!json.valueToTree(latest).equals(json.valueToTree(frozen))) throw changed();
        } catch (RuntimeException denied) {
            approvals.finish(actor, approvalId, "FAILED", "PLATFORM_APPROVAL_PRE_DISPATCH_FAILED");
            return refusal(denied);
        }
        ResponseEntity<?> response;
        try {
            @SuppressWarnings("unchecked") Map<String, Object> after = (Map<String, Object>) latest.get("after");
            response = embed.change(actor.session(), change, id, after);
        } catch (ResponseStatusException definite) {
            if (!definite.getStatusCode().is4xxClientError()) return unknown(actor, approvalId, change, id);
            approvals.finish(actor, approvalId, "FAILED", "PLATFORM_APPROVAL_EMBED_REJECTED");
            return ResponseEntity.status(definite.getStatusCode()).build();
        } catch (RuntimeException uncertain) {
            return unknown(actor, approvalId, change, id);
        }
        if (response != null && response.getStatusCode().is4xxClientError()) {
            approvals.finish(actor, approvalId, "FAILED", "PLATFORM_APPROVAL_EMBED_REJECTED");
            return ResponseEntity.status(response.getStatusCode()).build();
        }
        if (response == null || !response.getStatusCode().is2xxSuccessful()) return unknown(actor, approvalId, change, id);
        approvals.finish(actor, approvalId, "SUCCEEDED", null);
        return response;
    }

    private Map<String, Object> snapshot(ConnectionIdentity actor, Change change, long id, Map<String, Object> input) {
        Map<String, Object> request = input == null ? Map.of() : input;
        if (id <= 0) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("contractVersion", 1); result.put("operation", change.name()); result.put("targetId", id);
        result.put("request", request.isEmpty() ? null : request);
        if (change == Change.DELETE_PAGE) {
            if (!request.isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
            JsonNode page = find(array(embed.read(actor.session(), Read.PAGES, Map.of("limit", 1000))), id);
            Map<String, Object> before = new LinkedHashMap<>();
            for (String field : PAGE_FROZEN) before.put(field, json.convertValue(page.get(field), Object.class));
            List<Map<String, Object>> actions = new ArrayList<>();
            for (JsonNode action : array(embed.read(actor.session(), Read.PAGE_ACTIONS,
                    Map.of("projectCode", page.path("projectCode").asText(), "pageKey", page.path("pageKey").asText(), "limit", 1000)))) {
                actions.add(Map.of("actionKey", action.path("actionKey").asText(), "title", action.path("title").asText()));
            }
            before.put("actions", actions);
            result.put("before", before);
            result.put("after", null);
        } else {
            if (request.isEmpty() || !POLICY_FIELDS.containsAll(request.keySet())
                    || request.containsKey("tokenTtlSeconds") && !(request.get("tokenTtlSeconds") instanceof Integer)
                    || request.containsKey("status") && !(request.get("status") instanceof String)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
            }
            JsonNode policy = find(array(embed.read(actor.session(), Read.CREDENTIAL_POLICIES, Map.of("limit", 1000))), id);
            Map<String, Object> before = new LinkedHashMap<>();
            before.put("id", id); before.put("projectCode", policy.path("projectCode").asText()); before.put("appKey", policy.path("appKey").asText());
            before.put("allowedOrigins", list(policy.path("allowedOriginsJson")));
            before.put("allowedAgentIds", list(policy.path("allowedAgentIdsJson")));
            before.put("tokenTtlSeconds", policy.path("tokenTtlSeconds").isNumber() ? policy.path("tokenTtlSeconds").intValue() : null);
            before.put("status", policy.path("status").asText(null));
            // The owner replaces the whole policy, so fields the request leaves out keep their current values.
            Map<String, Object> after = new LinkedHashMap<>();
            after.put("allowedOrigins", request.containsKey("allowedOrigins") ? strings(request.get("allowedOrigins")) : before.get("allowedOrigins"));
            after.put("allowedAgentIds", request.containsKey("allowedAgentIds") ? strings(request.get("allowedAgentIds")) : before.get("allowedAgentIds"));
            after.put("tokenTtlSeconds", request.containsKey("tokenTtlSeconds") ? request.get("tokenTtlSeconds") : before.get("tokenTtlSeconds"));
            after.put("status", request.containsKey("status") ? request.get("status") : before.get("status"));
            result.put("before", before);
            result.put("after", after);
        }
        try { return json.readValue(json.writeValueAsBytes(result), MAP); }
        catch (Exception invalid) { throw changed(); }
    }

    private List<String> list(JsonNode encoded) {
        if (encoded == null || encoded.isNull() || encoded.asText().isBlank()) return List.of();
        try { return json.readValue(encoded.asText(), STRINGS); }
        catch (Exception invalid) { throw new ResponseStatusException(HttpStatus.BAD_GATEWAY); }
    }

    private static List<String> strings(Object value) {
        if (!(value instanceof List<?> values) || values.size() > 50) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        List<String> result = new ArrayList<>();
        for (Object item : values) {
            if (!(item instanceof String text) || text.isBlank() || text.length() > 256) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
            result.add(text.trim());
        }
        return result;
    }

    private JsonNode array(ResponseEntity<?> response) {
        if (response == null || !response.getStatusCode().is2xxSuccessful()) {
            throw new ResponseStatusException(response == null ? HttpStatus.BAD_GATEWAY : response.getStatusCode());
        }
        JsonNode body = json.valueToTree(response.getBody());
        if (!body.isArray()) throw new ResponseStatusException(HttpStatus.BAD_GATEWAY);
        return body;
    }

    private static JsonNode find(JsonNode rows, long id) {
        for (JsonNode row : rows) if (row.path("id").asLong() == id) return row;
        throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    }

    private static String subject(Change change, long id) {
        return (change == Change.DELETE_PAGE ? "EMBED_PAGE:" : "EMBED_POLICY:") + id;
    }

    private static ResponseStatusException changed() {
        return new ResponseStatusException(HttpStatus.CONFLICT, "Embed target changed");
    }

    private static ResponseEntity<?> refusal(RuntimeException failure) {
        int status = failure instanceof ResponseStatusException denied ? denied.getStatusCode().value()
                : failure instanceof IllegalArgumentException || failure instanceof ClassCastException
                        || failure instanceof NullPointerException ? 400 : 503;
        return status == 409 ? ResponseEntity.status(409).body(Map.of("code", STATE_CHANGED)) : ResponseEntity.status(status).build();
    }

    private ResponseEntity<?> unknown(ConnectionIdentity actor, String approvalId, Change change, long id) {
        approvals.finish(actor, approvalId, "UNKNOWN", "PLATFORM_APPROVAL_EXECUTION_UNKNOWN");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "UNKNOWN"); body.put("approvalId", approvalId); body.put("operation", change.name()); body.put("targetId", id);
        body.put("message", "嵌入修改结果未确认，请查询原审批并重新读取页面或接入策略；不要新建申请直接重试。");
        return ResponseEntity.accepted().body(body);
    }
}
