package com.enterprise.ai.control.mcp.application.platform;

import com.enterprise.ai.control.identity.PlatformAiApprovalService;
import com.enterprise.ai.control.identity.PlatformAiConnectionService.ConnectionIdentity;
import com.enterprise.ai.control.mcp.application.port.McpPlatformKnowledgeCatalogPort;
import com.enterprise.ai.control.mcp.application.port.McpPlatformKnowledgeChunkPort;
import com.enterprise.ai.control.mcp.application.port.McpPlatformKnowledgeChunkPort.Operation;
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
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Editing or disabling a knowledge chunk changes what every Agent retrieves, so an AI request freezes the chunk and the
 * exact new text or state for the owner's review and runs once; re-embedding only refreshes the vector and runs directly.
 * Every call first proves the caller holds knowledge write on the chunk's owner-signed resource.
 */
@Service
@RequiredArgsConstructor
public class McpPlatformKnowledgeChunkService {
    public static final String STATE_CHANGED = "KNOWLEDGE_CHUNK_CHANGED";
    private static final TypeReference<LinkedHashMap<String, Object>> MAP = new TypeReference<>() { };
    private static final int MAX_CONTENT = 8000;
    private final McpPlatformKnowledgeCatalogPort catalog;
    private final McpPlatformKnowledgeChunkPort chunks;
    private final PlatformAiApprovalService approvals;
    private final ObjectMapper json;

    public ResponseEntity<?> reembed(ConnectionIdentity actor, String fileId, long chunkId) {
        try { chunk(actor, fileId, chunkId); }
        catch (RuntimeException refused) { return refusal(refused); }
        var response = chunks.change(Operation.REEMBED, chunkId, Map.of());
        if (response != null && response.getStatusCode().is2xxSuccessful()) return ResponseEntity.ok(Map.of("reembedded", true, "chunkId", chunkId));
        if (response != null && response.getStatusCode().is4xxClientError()) return ResponseEntity.status(response.getStatusCode()).build();
        return ResponseEntity.accepted().body(Map.of("status", "UNKNOWN", "chunkId", chunkId,
                "message", "重新向量化结果未确认，请稍后读取切片；重新向量化可以安全地再次执行。"));
    }

    public ResponseEntity<?> request(ConnectionIdentity actor, Operation operation, String fileId, long chunkId,
                                     Map<String, Object> change, String invocationId) {
        Map<String, Object> frozen;
        try { frozen = snapshot(actor, operation, fileId, chunkId, change); }
        catch (RuntimeException refused) { return refusal(refused); }
        frozen.put("invocationId", invocationId);
        return ResponseEntity.ok(approvals.prepare(actor, PlatformAiApprovalService.KNOWLEDGE_CHUNK_CHANGE, subject(chunkId), frozen));
    }

    public ResponseEntity<?> execute(ConnectionIdentity actor, String approvalId) {
        var claim = approvals.claim(actor, approvalId, PlatformAiApprovalService.KNOWLEDGE_CHUNK_CHANGE);
        var frozen = claim.arguments();
        Operation operation;
        long chunkId;
        Map<String, Object> after;
        try {
            operation = Operation.valueOf(String.valueOf(frozen.get("operation")));
            if (operation == Operation.REEMBED) throw changed();
            chunkId = ((Number) frozen.get("chunkId")).longValue();
            if (!subject(chunkId).equals(claim.subject())) throw changed();
            after = json.convertValue(frozen.get("after"), MAP);
            var latest = snapshot(actor, operation, (String) frozen.get("fileId"), chunkId, after);
            latest.put("invocationId", frozen.get("invocationId"));
            if (!json.valueToTree(latest).equals(json.valueToTree(frozen))) throw changed();
        } catch (RuntimeException denied) {
            approvals.finish(actor, approvalId, "FAILED", "PLATFORM_APPROVAL_PRE_DISPATCH_FAILED");
            return refusal(denied);
        }
        ResponseEntity<?> response;
        try { response = chunks.change(operation, chunkId, after); }
        catch (RuntimeException lost) { return unknown(actor, approvalId, chunkId); }
        if (response != null && response.getStatusCode().is4xxClientError()) {
            approvals.finish(actor, approvalId, "FAILED", "PLATFORM_APPROVAL_KNOWLEDGE_CHUNK_REJECTED");
            return ResponseEntity.status(response.getStatusCode()).build();
        }
        if (!confirmed(response, chunkId, after)) return unknown(actor, approvalId, chunkId);
        approvals.finish(actor, approvalId, "SUCCEEDED", null);
        return response;
    }

    private Map<String, Object> snapshot(ConnectionIdentity actor, Operation operation, String fileId, long chunkId, Map<String, Object> change) {
        var current = chunk(actor, fileId, chunkId);
        JsonNode chunk = (JsonNode) current.get("chunk");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("contractVersion", 1); result.put("operation", operation.name());
        result.put("fileId", fileId); result.put("chunkId", chunkId); result.put("resource", current.get("resource"));
        String content = chunk.path("content").asText("");
        if (content.length() > MAX_CONTENT) throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE);
        Map<String, Object> before = new LinkedHashMap<>();
        before.put("chunkIndex", json.convertValue(chunk.get("chunkIndex"), Object.class));
        before.put("title", json.convertValue(chunk.get("title"), Object.class));
        before.put("content", content);
        before.put("contentSha256", sha256(content));
        before.put("enabled", chunk.path("enabled").asInt(1) == 1);
        before.put("vectorId", json.convertValue(chunk.get("vectorId"), Object.class));
        result.put("before", before);
        Map<String, Object> after = new LinkedHashMap<>();
        if (change == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        if (operation == Operation.UPDATE) {
            if (!java.util.Set.of("title", "content").containsAll(change.keySet())
                    || !(change.get("content") instanceof String text) || text.isBlank() || text.length() > MAX_CONTENT
                    || change.get("title") != null && !(change.get("title") instanceof String title && title.length() <= 256)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
            }
            after.put("title", change.get("title") == null ? before.get("title") : change.get("title"));
            after.put("content", text);
            if (text.equals(content) && java.util.Objects.equals(after.get("title"), before.get("title"))) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        } else if (operation == Operation.TOGGLE) {
            if (change.size() != 1 || !(change.get("enabled") instanceof Boolean enabled)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
            if (enabled.equals(before.get("enabled"))) throw changed();
            after.put("enabled", enabled);
        } else {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        }
        result.put("after", after);
        try { return json.readValue(json.writeValueAsBytes(result), MAP); }
        catch (Exception invalid) { throw changed(); }
    }

    private Map<String, Object> chunk(ConnectionIdentity actor, String fileId, long chunkId) {
        if (fileId == null || fileId.isBlank() || fileId.length() > 128 || chunkId < 1) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        var response = catalog.fileChunksForWrite(actor.session(), fileId);
        if (response == null || !response.getStatusCode().is2xxSuccessful()) {
            throw new ResponseStatusException(response == null ? HttpStatus.SERVICE_UNAVAILABLE : response.getStatusCode());
        }
        JsonNode body = json.valueToTree(response.getBody());
        for (JsonNode candidate : body.path("chunks")) {
            if (candidate.path("id").canConvertToLong() && candidate.path("id").asLong() == chunkId) {
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("resource", json.convertValue(body.get("resource"), Object.class));
                result.put("chunk", candidate);
                return result;
            }
        }
        throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    }

    private boolean confirmed(ResponseEntity<?> response, long chunkId, Map<String, Object> after) {
        if (response == null || !response.getStatusCode().is2xxSuccessful()) return false;
        JsonNode result = json.valueToTree(response.getBody());
        if (result == null || result.path("id").asLong() != chunkId) return false;
        if (after.containsKey("enabled")) return (result.path("enabled").asInt(-1) == 1) == (Boolean) after.get("enabled");
        return String.valueOf(after.get("content")).equals(result.path("content").asText());
    }

    private static String sha256(String text) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    private static String subject(long chunkId) { return "KNOWLEDGE_CHUNK:" + chunkId; }

    private static ResponseStatusException changed() {
        return new ResponseStatusException(HttpStatus.CONFLICT, "Knowledge chunk changed");
    }

    private static ResponseEntity<?> refusal(RuntimeException failure) {
        int status = failure instanceof ResponseStatusException denied ? denied.getStatusCode().value()
                : failure instanceof IllegalArgumentException || failure instanceof ClassCastException
                        || failure instanceof NullPointerException ? 409 : 503;
        return status == 409 ? ResponseEntity.status(409).body(Map.of("code", STATE_CHANGED)) : ResponseEntity.status(status).build();
    }

    private ResponseEntity<?> unknown(ConnectionIdentity actor, String approvalId, long chunkId) {
        approvals.finish(actor, approvalId, "UNKNOWN", "PLATFORM_APPROVAL_EXECUTION_UNKNOWN");
        return ResponseEntity.accepted().body(Map.of("status", "UNKNOWN", "approvalId", approvalId, "chunkId", chunkId,
                "message", "切片修改结果未确认，请查询原审批并重新读取文件切片；不要新建申请直接重试。"));
    }
}
