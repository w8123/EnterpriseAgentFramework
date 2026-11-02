package com.enterprise.ai.control.mcp.application.platform;

import com.enterprise.ai.control.identity.PlatformAiApprovalService;
import com.enterprise.ai.control.identity.PlatformAiConnectionService.ConnectionIdentity;
import com.enterprise.ai.control.mcp.application.port.McpPlatformKnowledgeBasePort;
import com.enterprise.ai.control.mcp.application.port.McpPlatformKnowledgeBasePort.Operation;
import com.enterprise.ai.control.mcp.application.port.McpPlatformKnowledgeCatalogPort;
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
 * Creating a knowledge base and retrieval tests run directly under the caller's knowledge grant on the exact base. Deleting
 * a base (with every file in it) or one file cannot be undone, so it goes to owner review and runs once against the base
 * and files as they were reviewed.
 */
@Service
@RequiredArgsConstructor
public class McpPlatformKnowledgeBaseService {
    public static final String STATE_CHANGED = "KNOWLEDGE_DELETE_TARGET_CHANGED";
    public enum Deletion { DELETE_BASE, DELETE_FILE }
    private static final TypeReference<LinkedHashMap<String, Object>> MAP = new TypeReference<>() { };
    private static final String IDENTIFIER = "[A-Za-z0-9][A-Za-z0-9._:-]{0,63}";
    private static final Set<String> CREATE_FIELDS = Set.of("code", "name", "description", "embeddingModelInstanceId",
            "rerankModelInstanceId", "llmModelInstanceId", "scope", "workspaceId", "projectCode");
    private static final List<String> BASE_FROZEN = List.of("code", "name", "scope", "workspaceId", "projectCode");
    // Parse progress and chunk counts move on their own; what the owner approves is which document disappears.
    private static final List<String> FILE_FROZEN = List.of("fileId", "fileName", "fileType", "fileSize");
    private final McpPlatformKnowledgeBasePort bases;
    private final McpPlatformKnowledgeCatalogPort catalog;
    private final PlatformAiApprovalService approvals;
    private final ObjectMapper json;

    public ResponseEntity<?> create(ConnectionIdentity actor, Map<String, Object> fields) {
        if (fields == null || !CREATE_FIELDS.containsAll(fields.keySet()) || !(fields.get("code") instanceof String code) || !code.matches(IDENTIFIER)
                || !(fields.get("name") instanceof String name) || name.isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        var response = bases.call(actor.session(), Operation.CREATE_BASE, null, fields);
        if (response != null && response.getStatusCode().value() == 503) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("status", "UNKNOWN"); body.put("code", code);
            body.put("message", "知识库新建结果未确认，请用 reachai_knowledge_bases_list 查找这个编码；不要直接重试。");
            return ResponseEntity.accepted().body(body);
        }
        return response == null ? ResponseEntity.status(HttpStatus.BAD_GATEWAY).build() : response;
    }

    public ResponseEntity<?> retrievalTest(ConnectionIdentity actor, String query, List<String> kbCodes, Integer topK, boolean recordHit) {
        if (query == null || query.isBlank() || query.length() > 2000 || kbCodes == null || kbCodes.isEmpty() || kbCodes.size() > 20
                || kbCodes.stream().anyMatch(code -> code == null || !code.matches(IDENTIFIER))) {
            return ResponseEntity.badRequest().build();
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("query", query); body.put("knowledgeBaseCodes", kbCodes); body.put("recordHit", recordHit);
        if (topK != null) body.put("topK", topK);
        var response = bases.call(actor.session(), Operation.RETRIEVAL_TEST, null, body);
        return response == null ? ResponseEntity.status(HttpStatus.BAD_GATEWAY).build() : response;
    }

    public ResponseEntity<?> requestDelete(ConnectionIdentity actor, Deletion deletion, String kbCode, String fileId, String invocationId) {
        Map<String, Object> frozen;
        try { frozen = snapshot(actor, deletion, kbCode, fileId); }
        catch (RuntimeException refused) { return refusal(refused); }
        frozen.put("invocationId", invocationId);
        return ResponseEntity.ok(approvals.prepare(actor, PlatformAiApprovalService.KNOWLEDGE_DELETE, subject(deletion, kbCode, fileId), frozen));
    }

    public ResponseEntity<?> executeDelete(ConnectionIdentity actor, String approvalId) {
        var claim = approvals.claim(actor, approvalId, PlatformAiApprovalService.KNOWLEDGE_DELETE);
        var frozen = claim.arguments();
        Deletion deletion;
        String kbCode;
        String fileId;
        try {
            deletion = Deletion.valueOf(String.valueOf(frozen.get("operation")));
            kbCode = (String) frozen.get("kbCode");
            fileId = (String) frozen.get("fileId");
            if (!subject(deletion, kbCode, fileId).equals(claim.subject())) throw changed();
            var latest = snapshot(actor, deletion, kbCode, fileId);
            latest.put("invocationId", frozen.get("invocationId"));
            if (!json.valueToTree(latest).equals(json.valueToTree(frozen))) throw changed();
        } catch (RuntimeException denied) {
            approvals.finish(actor, approvalId, "FAILED", "PLATFORM_APPROVAL_PRE_DISPATCH_FAILED");
            return refusal(denied);
        }
        ResponseEntity<?> response;
        try {
            response = deletion == Deletion.DELETE_BASE ? bases.call(actor.session(), Operation.DELETE_BASE, kbCode, null)
                    : bases.call(actor.session(), Operation.DELETE_FILE, fileId, null);
        } catch (RuntimeException uncertain) {
            return unknown(actor, approvalId, deletion, kbCode, fileId);
        }
        if (response == null || response.getStatusCode().value() == 503) return unknown(actor, approvalId, deletion, kbCode, fileId);
        if (!response.getStatusCode().is2xxSuccessful()) {
            approvals.finish(actor, approvalId, "FAILED", "PLATFORM_APPROVAL_KNOWLEDGE_DELETE_REJECTED");
            return ResponseEntity.status(response.getStatusCode()).build();
        }
        approvals.finish(actor, approvalId, "SUCCEEDED", null);
        return response;
    }

    private Map<String, Object> snapshot(ConnectionIdentity actor, Deletion deletion, String kbCode, String fileId) {
        if (kbCode == null || !kbCode.matches(IDENTIFIER) || (deletion == Deletion.DELETE_FILE) != (fileId != null)
                || fileId != null && !fileId.matches(IDENTIFIER)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        }
        // The same write check the console applies to this base or file, before anything is frozen.
        ok(deletion == Deletion.DELETE_BASE ? catalog.baseForWrite(actor.session(), kbCode) : catalog.fileChunksForWrite(actor.session(), fileId));
        JsonNode base = null;
        for (JsonNode item : array(catalog.read(actor.session(), McpPlatformKnowledgeCatalogPort.Operation.BASES, Map.of()))) {
            if (kbCode.equals(item.path("code").asText())) base = item;
        }
        if (base == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        List<Map<String, Object>> files = new ArrayList<>();
        Map<String, Object> target = null;
        for (JsonNode file : array(catalog.read(actor.session(), McpPlatformKnowledgeCatalogPort.Operation.FILES, Map.of("kbCode", kbCode)))) {
            Map<String, Object> summary = pick(file, FILE_FROZEN);
            files.add(summary);
            if (file.path("fileId").asText().equals(fileId)) target = summary;
        }
        if (deletion == Deletion.DELETE_FILE && target == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("contractVersion", 1); result.put("operation", deletion.name()); result.put("kbCode", kbCode); result.put("fileId", fileId);
        result.put("base", pick(base, BASE_FROZEN));
        if (deletion == Deletion.DELETE_BASE) result.put("files", files);
        else result.put("file", target);
        try { return json.readValue(json.writeValueAsBytes(result), MAP); }
        catch (Exception invalid) { throw changed(); }
    }

    private Map<String, Object> pick(JsonNode node, List<String> fields) {
        Map<String, Object> picked = new LinkedHashMap<>();
        for (String field : fields) picked.put(field, json.convertValue(node.get(field), Object.class));
        return picked;
    }

    private JsonNode array(ResponseEntity<?> response) {
        ok(response);
        JsonNode body = json.valueToTree(response.getBody());
        if (!body.isArray()) throw new ResponseStatusException(HttpStatus.BAD_GATEWAY);
        return body;
    }

    private static void ok(ResponseEntity<?> response) {
        if (response == null || !response.getStatusCode().is2xxSuccessful()) {
            throw new ResponseStatusException(response == null ? HttpStatus.BAD_GATEWAY : response.getStatusCode());
        }
    }

    private static String subject(Deletion deletion, String kbCode, String fileId) {
        return deletion == Deletion.DELETE_BASE ? "KNOWLEDGE_BASE:" + kbCode : "KNOWLEDGE_FILE:" + kbCode + "/" + fileId;
    }

    private static ResponseStatusException changed() {
        return new ResponseStatusException(HttpStatus.CONFLICT, "Knowledge delete target changed");
    }

    private static ResponseEntity<?> refusal(RuntimeException failure) {
        int status = failure instanceof ResponseStatusException denied ? denied.getStatusCode().value()
                : failure instanceof IllegalArgumentException || failure instanceof ClassCastException
                        || failure instanceof NullPointerException ? 400 : 503;
        return status == 409 ? ResponseEntity.status(409).body(Map.of("code", STATE_CHANGED)) : ResponseEntity.status(status).build();
    }

    private ResponseEntity<?> unknown(ConnectionIdentity actor, String approvalId, Deletion deletion, String kbCode, String fileId) {
        approvals.finish(actor, approvalId, "UNKNOWN", "PLATFORM_APPROVAL_EXECUTION_UNKNOWN");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "UNKNOWN"); body.put("approvalId", approvalId); body.put("operation", deletion.name());
        body.put("kbCode", kbCode); body.put("fileId", fileId);
        body.put("message", "删除结果未确认，请查询原审批并重新读取知识库或文件列表；不要新建申请直接重试。");
        return ResponseEntity.accepted().body(body);
    }
}
