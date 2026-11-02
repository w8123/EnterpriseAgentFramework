package com.enterprise.ai.control.mcp.application.platform;

import com.enterprise.ai.control.identity.PlatformAiApprovalService;
import com.enterprise.ai.control.identity.PlatformAiConnectionService.ConnectionIdentity;
import com.enterprise.ai.control.mcp.application.port.McpPlatformKnowledgeImportPort;
import com.enterprise.ai.control.mcp.application.port.McpPlatformKnowledgeImportPort.Operation;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import feign.FeignException;
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
import java.util.Set;

/**
 * Document import jobs a person uploaded can be followed and finished by their AI tool. Retrying a failed job only
 * re-runs the person's own submission, so it dispatches directly once the job is still in the state the caller saw.
 * Committing makes the parsed content searchable and cancelling discards the uploaded source, so both freeze the job
 * (and, for a commit, the reviewed preview) for the owner's review and then run once.
 */
@Service
@RequiredArgsConstructor
public class McpPlatformKnowledgeImportService {
    public static final String STATE_CHANGED = "KNOWLEDGE_IMPORT_JOB_STATE_CHANGED";
    private static final TypeReference<LinkedHashMap<String, Object>> MAP = new TypeReference<>() { };
    private static final Set<String> RETRYABLE = Set.of("FAILED", "RETRY_WAIT");
    private static final Set<String> CANCELLABLE = Set.of("QUEUED", "PARSING", "PARSED", "RETRY_WAIT");
    private final McpPlatformKnowledgeImportPort jobs;
    private final PlatformAiApprovalService approvals;
    private final ObjectMapper json;

    public ResponseEntity<?> job(ConnectionIdentity actor, String jobId, boolean includePreview) {
        return jobs.job(actor.session(), jobId, includePreview);
    }

    /** Re-parsing replaces only the uncommitted preview of one file; the job still waits for an approved commit. */
    public ResponseEntity<?> reparse(ConnectionIdentity actor, String fileId) {
        return PlatformAiDirectWrites.once(() -> jobs.reparse(actor.session(), fileId),
                "重新解析结果未确认，请用 reachai_knowledge_import_job_get 读取导入任务；不要直接重试。");
    }

    public ResponseEntity<?> retry(ConnectionIdentity actor, String jobId, String expectedStatus) {
        try {
            JsonNode job = read(actor, jobId, false);
            String status = job.path("status").asText();
            if (!RETRYABLE.contains(status) || !status.equals(expectedStatus)) throw changed();
        } catch (RuntimeException refused) { return refusal(refused); }
        ResponseEntity<?> response;
        try { response = jobs.mutate(actor.session(), jobId, Operation.RETRY); }
        catch (RuntimeException failure) {
            var denied = refusal(failure);
            return denied.getStatusCode().is4xxClientError() ? denied : unknownRetry(jobId);
        }
        if (response != null && response.getStatusCode().is4xxClientError()) return refusal(new ResponseStatusException(response.getStatusCode()));
        if (response == null || !response.getStatusCode().is2xxSuccessful()) return unknownRetry(jobId);
        return response;
    }

    public ResponseEntity<?> request(ConnectionIdentity actor, Operation operation, String jobId, String invocationId) {
        Map<String, Object> frozen;
        try { frozen = snapshot(actor, operation, jobId); }
        catch (RuntimeException refused) { return refusal(refused); }
        frozen.put("invocationId", invocationId);
        return ResponseEntity.ok(approvals.prepare(actor, action(operation), subject(jobId), frozen));
    }

    public ResponseEntity<?> execute(ConnectionIdentity actor, Operation operation, String approvalId) {
        var claim = approvals.claim(actor, approvalId, action(operation));
        var frozen = claim.arguments();
        String jobId;
        try {
            if (!(frozen.get("jobId") instanceof String id) || !subject(id).equals(claim.subject())) throw changed();
            jobId = id;
            var latest = snapshot(actor, operation, jobId);
            latest.put("invocationId", frozen.get("invocationId"));
            if (!json.valueToTree(latest).equals(json.valueToTree(frozen))) throw changed();
        } catch (RuntimeException denied) {
            approvals.finish(actor, approvalId, "FAILED", "PLATFORM_APPROVAL_PRE_DISPATCH_FAILED");
            return refusal(denied);
        }
        ResponseEntity<?> response;
        try { response = jobs.mutate(actor.session(), jobId, operation); }
        catch (RuntimeException failure) {
            var denied = refusal(failure);
            if (denied.getStatusCode().is4xxClientError()) {
                approvals.finish(actor, approvalId, "FAILED", "PLATFORM_APPROVAL_IMPORT_REJECTED");
                return denied;
            }
            return unknown(actor, approvalId, jobId);
        }
        if (response != null && response.getStatusCode().is4xxClientError()) {
            approvals.finish(actor, approvalId, "FAILED", "PLATFORM_APPROVAL_IMPORT_REJECTED");
            return refusal(new ResponseStatusException(response.getStatusCode()));
        }
        if (!confirmed(response, operation, jobId)) return unknown(actor, approvalId, jobId);
        approvals.finish(actor, approvalId, "SUCCEEDED", null);
        return response;
    }

    private Map<String, Object> snapshot(ConnectionIdentity actor, Operation operation, String jobId) {
        boolean commit = operation == Operation.COMMIT;
        if (!commit && operation != Operation.CANCEL) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        JsonNode job = read(actor, jobId, commit);
        String status = job.path("status").asText();
        if (commit ? !"PARSED".equals(status) : !CANCELLABLE.contains(status)) throw changed();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("contractVersion", 1); result.put("operation", operation.name()); result.put("jobId", jobId);
        for (String field : new String[] {"fileId", "replaceFileId", "knowledgeBaseCode", "fileName", "fileType", "createTime"}) {
            result.put(field, json.convertValue(job.get(field), Object.class));
        }
        if (commit) {
            // A cancel stays the person's intent while the job keeps parsing; a commit is bound to the exact preview reviewed.
            result.put("status", status); result.put("providerType", json.convertValue(job.get("providerType"), Object.class));
            result.put("providerVersion", json.convertValue(job.get("providerVersion"), Object.class));
            result.put("parsedAt", json.convertValue(job.get("parsedAt"), Object.class));
            JsonNode preview = job.path("preview");
            if (!preview.isObject() || !preview.path("totalChunks").isInt()) throw changed();
            Map<String, Object> reviewed = new LinkedHashMap<>();
            reviewed.put("chunkStrategy", json.convertValue(preview.get("chunkStrategy"), Object.class));
            reviewed.put("chunkSize", preview.path("chunkSize").asInt()); reviewed.put("chunkOverlap", preview.path("chunkOverlap").asInt());
            reviewed.put("totalChunks", preview.path("totalChunks").asInt()); reviewed.put("chunksSha256", sha256(preview.path("chunks")));
            result.put("preview", reviewed);
        }
        try { return json.readValue(json.writeValueAsBytes(result), MAP); }
        catch (Exception invalid) { throw changed(); }
    }

    private JsonNode read(ConnectionIdentity actor, String jobId, boolean includePreview) {
        var response = jobs.job(actor.session(), jobId, includePreview);
        if (response == null || !response.getStatusCode().is2xxSuccessful()) {
            throw new ResponseStatusException(response == null ? HttpStatus.SERVICE_UNAVAILABLE : response.getStatusCode());
        }
        JsonNode job = json.valueToTree(response.getBody());
        if (job == null || !jobId.equals(job.path("jobId").asText()) || !job.path("status").isTextual()) throw changed();
        return job;
    }

    private boolean confirmed(ResponseEntity<?> response, Operation operation, String jobId) {
        if (response == null || !response.getStatusCode().is2xxSuccessful()) return false;
        if (operation == Operation.CANCEL) return true;
        JsonNode job = json.valueToTree(response.getBody());
        return job != null && jobId.equals(job.path("jobId").asText()) && job.path("autoCommit").asBoolean(false);
    }

    private String sha256(JsonNode value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json.writeValueAsString(value).getBytes(StandardCharsets.UTF_8))); }
        catch (Exception unavailable) { throw new IllegalStateException(unavailable); }
    }

    private static String action(Operation operation) {
        return switch (operation) {
            case COMMIT -> PlatformAiApprovalService.KNOWLEDGE_IMPORT_COMMIT;
            case CANCEL -> PlatformAiApprovalService.KNOWLEDGE_IMPORT_CANCEL;
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        };
    }

    private static String subject(String jobId) { return "KNOWLEDGE_IMPORT_JOB:" + jobId; }

    private static ResponseStatusException changed() {
        return new ResponseStatusException(HttpStatus.CONFLICT, "Knowledge import job changed");
    }

    private static ResponseEntity<?> refusal(RuntimeException failure) {
        int status = failure instanceof ResponseStatusException denied ? denied.getStatusCode().value()
                : failure instanceof FeignException remote && remote.status() > 0 ? remote.status()
                : failure instanceof IllegalArgumentException ? 400 : 503;
        return status == 409 ? ResponseEntity.status(409).body(Map.of("code", STATE_CHANGED)) : ResponseEntity.status(status).build();
    }

    private static ResponseEntity<?> unknownRetry(String jobId) {
        return ResponseEntity.accepted().body(Map.of("status", "UNKNOWN", "jobId", jobId,
                "message", "重试结果未确认，请重新读取导入任务状态；不要直接再次重试。"));
    }

    private ResponseEntity<?> unknown(ConnectionIdentity actor, String approvalId, String jobId) {
        approvals.finish(actor, approvalId, "UNKNOWN", "PLATFORM_APPROVAL_EXECUTION_UNKNOWN");
        return ResponseEntity.accepted().body(Map.of("status", "UNKNOWN", "approvalId", approvalId, "jobId", jobId,
                "message", "导入任务操作结果未确认，请查询原审批并重新读取导入任务；不要新建申请直接重试。"));
    }
}
