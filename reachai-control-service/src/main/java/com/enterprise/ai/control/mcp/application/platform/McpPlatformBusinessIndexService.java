package com.enterprise.ai.control.mcp.application.platform;

import com.enterprise.ai.control.identity.PlatformAiApprovalService;
import com.enterprise.ai.control.identity.PlatformAiConnectionService.ConnectionIdentity;
import com.enterprise.ai.control.identity.PlatformPermissions;
import com.enterprise.ai.control.mcp.application.port.McpPlatformKnowledgePort;
import com.enterprise.ai.control.mcp.application.port.McpPlatformKnowledgePort.IndexChange;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * A new business index starts empty and a rebuild re-embeds records already pushed by the business system, so both are
 * as direct as the console buttons. Editing an index changes how every Agent that searches it sees its records, and
 * deleting one drops all of them, so those go to owner review and run once against the reviewed definition.
 */
@Service
@RequiredArgsConstructor
public class McpPlatformBusinessIndexService {
    public static final String STATE_CHANGED = "BUSINESS_INDEX_CHANGED";
    public enum Change { UPDATE, DELETE }
    private static final TypeReference<LinkedHashMap<String, Object>> MAP = new TypeReference<>() { };
    private static final Set<String> FIELDS = Set.of("indexCode", "indexName", "sourceSystem", "textTemplate", "fieldSchema",
            "embeddingModelInstanceId", "dimension", "chunkSize", "chunkOverlap", "splitType", "remark", "projectId", "projectCode");
    private static final Set<String> EDITABLE = Set.of("indexName", "sourceSystem", "textTemplate", "fieldSchema",
            "embeddingModelInstanceId", "dimension", "chunkSize", "chunkOverlap", "splitType", "remark");
    // Business systems keep pushing records, so counts move on their own and are not part of what the owner approves.
    private static final Set<String> VOLATILE = Set.of("recordCount", "attachmentChunkCount", "attachmentRecordCount", "totalVectorCount");
    private final McpPlatformKnowledgePort knowledge;
    private final PlatformAiApprovalService approvals;
    private final ObjectMapper json;

    public ResponseEntity<?> create(ConnectionIdentity actor, Map<String, Object> index) {
        requireWrite(actor);
        if (index == null || !FIELDS.containsAll(index.keySet()) || !(index.get("indexCode") instanceof String)) {
            return ResponseEntity.badRequest().build();
        }
        return direct(actor, IndexChange.CREATE, (String) index.get("indexCode"), index);
    }

    public ResponseEntity<?> rebuild(ConnectionIdentity actor, String indexCode) {
        requireWrite(actor);
        return direct(actor, IndexChange.REBUILD, indexCode, null);
    }

    private ResponseEntity<?> direct(ConnectionIdentity actor, IndexChange change, String indexCode, Map<String, Object> body) {
        ResponseEntity<?> response;
        try { response = knowledge.changeBusinessIndex(actor.session(), change, indexCode, body); }
        catch (ResponseStatusException definite) { throw definite; }
        catch (RuntimeException lost) { response = null; }
        if (response == null || response.getStatusCode().is5xxServerError()) {
            Map<String, Object> unknown = new LinkedHashMap<>();
            unknown.put("status", "UNKNOWN"); unknown.put("indexCode", indexCode);
            unknown.put("message", "结果未确认，请先用 reachai_business_index_get 或 reachai_business_index_stats 查看，不要直接再次执行。");
            return ResponseEntity.accepted().body(unknown);
        }
        return rejected(response) ? ResponseEntity.status(HttpStatus.CONFLICT).body(response.getBody()) : response;
    }

    public ResponseEntity<?> request(ConnectionIdentity actor, Change change, String indexCode, Map<String, Object> fields, String invocationId) {
        Map<String, Object> frozen;
        try { frozen = snapshot(actor, change, indexCode, fields); }
        catch (RuntimeException refused) { return refusal(refused); }
        frozen.put("invocationId", invocationId);
        return ResponseEntity.ok(approvals.prepare(actor, PlatformAiApprovalService.BUSINESS_INDEX_CHANGE, "BUSINESS_INDEX:" + indexCode, frozen));
    }

    public ResponseEntity<?> execute(ConnectionIdentity actor, String approvalId) {
        var claim = approvals.claim(actor, approvalId, PlatformAiApprovalService.BUSINESS_INDEX_CHANGE);
        var frozen = claim.arguments();
        Change change;
        String indexCode;
        Map<String, Object> after;
        try {
            change = Change.valueOf(String.valueOf(frozen.get("operation")));
            indexCode = (String) frozen.get("indexCode");
            if (!("BUSINESS_INDEX:" + indexCode).equals(claim.subject())) throw changed();
            Map<String, Object> request = frozen.get("request") == null ? null : json.convertValue(frozen.get("request"), MAP);
            var latest = snapshot(actor, change, indexCode, request);
            latest.put("invocationId", frozen.get("invocationId"));
            if (!json.valueToTree(latest).equals(json.valueToTree(frozen))) throw changed();
            after = latest.get("after") == null ? null : json.convertValue(latest.get("after"), MAP);
        } catch (RuntimeException denied) {
            approvals.finish(actor, approvalId, "FAILED", "PLATFORM_APPROVAL_PRE_DISPATCH_FAILED");
            return refusal(denied);
        }
        ResponseEntity<?> response;
        try {
            response = knowledge.changeBusinessIndex(actor.session(), change == Change.UPDATE ? IndexChange.UPDATE : IndexChange.DELETE, indexCode, after);
        } catch (ResponseStatusException definite) {
            if (!definite.getStatusCode().is4xxClientError()) return unknown(actor, approvalId, indexCode);
            approvals.finish(actor, approvalId, "FAILED", "PLATFORM_APPROVAL_BUSINESS_INDEX_REJECTED");
            return ResponseEntity.status(definite.getStatusCode()).build();
        } catch (RuntimeException uncertain) {
            return unknown(actor, approvalId, indexCode);
        }
        if (response != null && (response.getStatusCode().is4xxClientError() || response.getStatusCode().is2xxSuccessful() && rejected(response))) {
            approvals.finish(actor, approvalId, "FAILED", "PLATFORM_APPROVAL_BUSINESS_INDEX_REJECTED");
            return response.getStatusCode().is4xxClientError() ? response : ResponseEntity.status(HttpStatus.CONFLICT).body(response.getBody());
        }
        if (response == null || !response.getStatusCode().is2xxSuccessful()) return unknown(actor, approvalId, indexCode);
        approvals.finish(actor, approvalId, "SUCCEEDED", null);
        return response;
    }

    private Map<String, Object> snapshot(ConnectionIdentity actor, Change change, String indexCode, Map<String, Object> input) {
        requireWrite(actor);
        if (indexCode == null || !indexCode.matches("[A-Za-z][A-Za-z0-9_]{1,62}")) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        Map<String, Object> request = input == null ? Map.of() : input;
        var detail = knowledge.getBusinessIndex(actor.session(), indexCode);
        if (detail == null || !detail.getStatusCode().is2xxSuccessful() || detail.getBody() == null) {
            throw new ResponseStatusException(detail == null ? HttpStatus.SERVICE_UNAVAILABLE : detail.getStatusCode());
        }
        JsonNode root = json.valueToTree(detail.getBody());
        JsonNode data = root.has("data") ? root.path("data") : root;
        if (!data.isObject() || !indexCode.equals(data.path("indexCode").asText())) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        Map<String, Object> before = json.convertValue(data, MAP);
        VOLATILE.forEach(before::remove);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("contractVersion", 1); result.put("operation", change.name()); result.put("indexCode", indexCode);
        result.put("request", request.isEmpty() ? null : request);
        result.put("before", before);
        if (change == Change.UPDATE) {
            if (request.isEmpty() || !EDITABLE.containsAll(request.keySet())) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
            Map<String, Object> after = new LinkedHashMap<>();
            after.put("indexCode", indexCode);
            for (String field : EDITABLE) after.put(field, request.containsKey(field) ? request.get(field) : before.get(field));
            boolean unchanged = EDITABLE.stream().allMatch(field -> String.valueOf(after.get(field)).equals(String.valueOf(before.get(field))));
            if (unchanged) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
            result.put("after", after);
        } else {
            if (!request.isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
            result.put("after", null);
        }
        try { return json.readValue(json.writeValueAsBytes(result), MAP); }
        catch (Exception invalid) { throw changed(); }
    }

    /** Knowledge answers with an ApiResult envelope; a non-success code inside a 200 is a definite refusal. */
    private boolean rejected(ResponseEntity<?> response) {
        if (response.getBody() == null) return false;
        JsonNode code = json.valueToTree(response.getBody()).path("code");
        return code.isNumber() && code.asInt() != 200 && code.asInt() != 0;
    }

    private static void requireWrite(ConnectionIdentity actor) {
        if (!actor.session().hasGlobalPermission(PlatformPermissions.KNOWLEDGE_WRITE)) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
    }

    private static ResponseStatusException changed() {
        return new ResponseStatusException(HttpStatus.CONFLICT, "Business index changed");
    }

    private static ResponseEntity<?> refusal(RuntimeException failure) {
        int status = failure instanceof ResponseStatusException denied ? denied.getStatusCode().value()
                : failure instanceof IllegalArgumentException || failure instanceof ClassCastException
                        || failure instanceof NullPointerException ? 400 : 503;
        return status == 409 ? ResponseEntity.status(409).body(Map.of("code", STATE_CHANGED)) : ResponseEntity.status(status).build();
    }

    private ResponseEntity<?> unknown(ConnectionIdentity actor, String approvalId, String indexCode) {
        approvals.finish(actor, approvalId, "UNKNOWN", "PLATFORM_APPROVAL_EXECUTION_UNKNOWN");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "UNKNOWN"); body.put("approvalId", approvalId); body.put("indexCode", indexCode);
        body.put("message", "业务索引修改结果未确认，请查询原审批并重新读取业务索引；不要新建申请直接重试。");
        return ResponseEntity.accepted().body(body);
    }
}
