package com.enterprise.ai.control.mcp.application.platform;

import com.enterprise.ai.control.identity.PlatformAiApprovalService;
import com.enterprise.ai.control.identity.PlatformAiConnectionService.ConnectionIdentity;
import com.enterprise.ai.control.mcp.application.port.McpPlatformCatalogResourcePort;
import com.enterprise.ai.control.mcp.application.port.McpPlatformCatalogResourcePort.Area;
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
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Group, classification and semantic edits carry the revision the caller read and only fill or refine catalog metadata,
 * and graph inference only adds candidates, so they run directly. Regenerating a graph drops every relation and layout,
 * including ones people confirmed, and deleting a relation removes it for good, so both go to owner review and run once
 * against what was reviewed. A lost result is reported as unknown, never resent.
 */
@Service
@RequiredArgsConstructor
public class McpPlatformCatalogResourceService {
    public static final String STATE_CHANGED = "API_GRAPH_CHANGED";
    public enum GraphChange { REGENERATE, DELETE_EDGE }
    private static final TypeReference<LinkedHashMap<String, Object>> MAP = new TypeReference<>() { };
    private static final List<String> EDGE_FROZEN = List.of("id", "sourceNodeId", "targetNodeId", "kind", "source", "status", "confirmedBy", "note", "enabled");
    private static final int LISTED_HUMAN_EDGES = 100;
    private final McpPlatformCatalogResourcePort catalog;
    private final PlatformAiApprovalService approvals;
    private final ObjectMapper json;

    public ResponseEntity<?> read(ConnectionIdentity actor, Area area, long projectId, String path, Map<String, String> query) {
        return catalog.call(actor.session(), area, "GET", projectId, path, query, null);
    }

    public ResponseEntity<?> write(ConnectionIdentity actor, Area area, String method, long projectId, String path,
                                   Map<String, String> query, Object body) {
        return PlatformAiDirectWrites.once(() -> catalog.call(actor.session(), area, method, projectId, path, query, body),
                "修改结果未确认，请重新读取后再决定；不要直接重试。");
    }

    public ResponseEntity<?> request(ConnectionIdentity actor, GraphChange change, long projectId, Long edgeId, String invocationId) {
        Map<String, Object> frozen;
        try { frozen = snapshot(actor, change, projectId, edgeId); }
        catch (RuntimeException refused) { return refusal(refused); }
        frozen.put("invocationId", invocationId);
        return ResponseEntity.ok(approvals.prepare(actor, PlatformAiApprovalService.API_GRAPH_CHANGE, subject(change, projectId, edgeId), frozen));
    }

    public ResponseEntity<?> execute(ConnectionIdentity actor, String approvalId) {
        var claim = approvals.claim(actor, approvalId, PlatformAiApprovalService.API_GRAPH_CHANGE);
        var frozen = claim.arguments();
        GraphChange change;
        long projectId;
        Long edgeId;
        try {
            change = GraphChange.valueOf(String.valueOf(frozen.get("operation")));
            projectId = ((Number) frozen.get("projectId")).longValue();
            edgeId = frozen.get("edgeId") == null ? null : ((Number) frozen.get("edgeId")).longValue();
            if (!subject(change, projectId, edgeId).equals(claim.subject())) throw changed();
            Map<String, Object> latest = snapshot(actor, change, projectId, edgeId);
            latest.put("invocationId", frozen.get("invocationId"));
            if (!json.valueToTree(latest).equals(json.valueToTree(frozen))) throw changed();
        } catch (RuntimeException denied) {
            approvals.finish(actor, approvalId, "FAILED", "PLATFORM_APPROVAL_PRE_DISPATCH_FAILED");
            return refusal(denied);
        }
        String message = "关系图修改结果未确认，请查询原审批并重新读取关系图；不要新建申请直接重试。";
        ResponseEntity<?> response;
        try {
            response = change == GraphChange.REGENERATE
                    ? catalog.call(actor.session(), Area.API_GRAPH, "POST", projectId, "/regenerate", Map.of(), null)
                    : catalog.call(actor.session(), Area.API_GRAPH, "DELETE", projectId, "/edges/" + edgeId, Map.of(), null);
        } catch (ResponseStatusException definite) {
            if (!definite.getStatusCode().is4xxClientError()) return finishUnknown(actor, approvalId, message);
            approvals.finish(actor, approvalId, "FAILED", "PLATFORM_APPROVAL_API_GRAPH_REJECTED");
            return ResponseEntity.status(definite.getStatusCode()).build();
        } catch (RuntimeException uncertain) {
            return finishUnknown(actor, approvalId, message);
        }
        if (response != null && response.getStatusCode().is4xxClientError()) {
            approvals.finish(actor, approvalId, "FAILED", "PLATFORM_APPROVAL_API_GRAPH_REJECTED");
            return ResponseEntity.status(response.getStatusCode()).build();
        }
        if (response == null || !response.getStatusCode().is2xxSuccessful()) return finishUnknown(actor, approvalId, message);
        approvals.finish(actor, approvalId, "SUCCEEDED", null);
        return response;
    }

    private Map<String, Object> snapshot(ConnectionIdentity actor, GraphChange change, long projectId, Long edgeId) {
        if (projectId <= 0 || (change == GraphChange.DELETE_EDGE) != (edgeId != null) || edgeId != null && edgeId <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        }
        ResponseEntity<?> response = catalog.call(actor.session(), Area.API_GRAPH, "GET", projectId, "/snapshot", Map.of(), null);
        if (response == null || !response.getStatusCode().is2xxSuccessful()) {
            throw new ResponseStatusException(response == null ? HttpStatus.BAD_GATEWAY : response.getStatusCode());
        }
        JsonNode graph = json.valueToTree(response.getBody());
        if (!graph.path("nodes").isArray() || !graph.path("edges").isArray()) throw new ResponseStatusException(HttpStatus.BAD_GATEWAY);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("contractVersion", 1); result.put("operation", change.name()); result.put("projectId", projectId); result.put("edgeId", edgeId);
        if (change == GraphChange.DELETE_EDGE) {
            JsonNode edge = null;
            for (JsonNode row : graph.path("edges")) if (row.path("id").asLong() == edgeId) edge = row;
            if (edge == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
            result.put("edge", edge(edge));
        } else {
            // The owner reviews what disappears: counts, the relations people curated, and a digest of every relation.
            Map<String, Integer> byStatus = new TreeMap<>();
            List<Map<String, Object>> human = new ArrayList<>();
            List<String> tuples = new ArrayList<>();
            for (JsonNode row : graph.path("edges")) {
                String status = row.path("status").asText("");
                byStatus.merge(status.isBlank() ? "UNKNOWN" : status, 1, Integer::sum);
                if (!"CANDIDATE".equals(status) && !"REJECTED".equals(status) && human.size() < LISTED_HUMAN_EDGES) human.add(edge(row));
                tuples.add(json.valueToTree(edge(row)).toString());
            }
            tuples.sort(null);
            result.put("nodeCount", graph.path("nodes").size());
            result.put("edgeCount", graph.path("edges").size());
            result.put("layoutCount", graph.path("layouts").size());
            result.put("edgesByStatus", byStatus);
            result.put("curatedEdges", human);
            result.put("edgeDigest", digest(String.join("\n", tuples)));
        }
        try { return json.readValue(json.writeValueAsBytes(result), MAP); }
        catch (Exception invalid) { throw changed(); }
    }

    private Map<String, Object> edge(JsonNode row) {
        Map<String, Object> edge = new LinkedHashMap<>();
        for (String field : EDGE_FROZEN) edge.put(field, json.convertValue(row.get(field), Object.class));
        return edge;
    }

    private static String digest(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception unavailable) { throw new IllegalStateException(unavailable); }
    }

    private static String subject(GraphChange change, long projectId, Long edgeId) {
        return change == GraphChange.REGENERATE ? "API_GRAPH:" + projectId : "API_GRAPH_EDGE:" + projectId + ":" + edgeId;
    }

    private static ResponseStatusException changed() {
        return new ResponseStatusException(HttpStatus.CONFLICT, "API graph changed");
    }

    private static ResponseEntity<?> refusal(RuntimeException failure) {
        int status = failure instanceof ResponseStatusException denied ? denied.getStatusCode().value()
                : failure instanceof IllegalArgumentException || failure instanceof ClassCastException
                        || failure instanceof NullPointerException ? 400 : 503;
        return status == 409 ? ResponseEntity.status(409).body(Map.of("code", STATE_CHANGED)) : ResponseEntity.status(status).build();
    }

    private ResponseEntity<?> finishUnknown(ConnectionIdentity actor, String approvalId, String message) {
        approvals.finish(actor, approvalId, "UNKNOWN", "PLATFORM_APPROVAL_EXECUTION_UNKNOWN");
        return unknown(approvalId, message);
    }

    private static ResponseEntity<?> unknown(String approvalId, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "UNKNOWN");
        if (approvalId != null) body.put("approvalId", approvalId);
        body.put("message", message);
        return ResponseEntity.accepted().body(body);
    }
}
