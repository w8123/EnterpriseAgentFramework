package com.enterprise.ai.control.mcp.application.platform;

import com.enterprise.ai.control.identity.PlatformAiApprovalService;
import com.enterprise.ai.control.identity.PlatformAiConnectionService.ConnectionIdentity;
import com.enterprise.ai.control.mcp.application.port.McpPlatformSkillCatalogPort;
import com.enterprise.ai.control.mcp.application.port.McpPlatformSkillGovernancePort;
import com.enterprise.ai.control.mcp.application.port.McpPlatformSkillGovernancePort.Operation;
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
import java.util.Map;
import java.util.Set;

/**
 * Reviewing, publishing, switching the default, deprecating and revoking a Skill version decide what every bound Agent
 * may load, so an AI request freezes the version's package digests, its state and the Skill's default version for the
 * owner's review; the console transition then runs once under its own row lock and state machine.
 */
@Service
@RequiredArgsConstructor
public class McpPlatformSkillGovernanceService {
    public static final String STATE_CHANGED = "SKILL_VERSION_STATE_CHANGED";
    private static final TypeReference<LinkedHashMap<String, Object>> MAP = new TypeReference<>() { };
    private static final Map<Operation, Set<String>> FROM = Map.of(
            Operation.REVIEW, Set.of("REVIEW_PENDING", "REJECTED"),
            Operation.PUBLISH, Set.of("APPROVED"),
            Operation.SET_DEFAULT, Set.of("PUBLISHED"),
            Operation.DEPRECATE, Set.of("PUBLISHED"),
            Operation.REVOKE, Set.of("REVIEW_PENDING", "APPROVED", "REJECTED", "PUBLISHED", "DEPRECATED"));
    private final McpPlatformSkillCatalogPort catalog;
    private final McpPlatformSkillGovernancePort governance;
    private final PlatformAiApprovalService approvals;
    private final ObjectMapper json;

    public ResponseEntity<?> request(ConnectionIdentity actor, Operation operation, long skillId, long versionId,
                                     String decision, String comment, String invocationId) {
        Map<String, Object> frozen;
        try { frozen = snapshot(actor, operation, skillId, versionId, decision, comment); }
        catch (RuntimeException refused) { return refusal(refused); }
        frozen.put("invocationId", invocationId);
        return ResponseEntity.ok(approvals.prepare(actor, PlatformAiApprovalService.SKILL_VERSION_GOVERNANCE, subject(skillId, versionId), frozen));
    }

    public ResponseEntity<?> execute(ConnectionIdentity actor, String approvalId) {
        var claim = approvals.claim(actor, approvalId, PlatformAiApprovalService.SKILL_VERSION_GOVERNANCE);
        var frozen = claim.arguments();
        Operation operation;
        long skillId, versionId;
        String decision, comment;
        try {
            operation = Operation.valueOf(String.valueOf(frozen.get("operation")));
            skillId = ((Number) frozen.get("skillId")).longValue(); versionId = ((Number) frozen.get("versionId")).longValue();
            decision = (String) frozen.get("decision"); comment = (String) frozen.get("comment");
            if (!subject(skillId, versionId).equals(claim.subject())) throw changed();
            var latest = snapshot(actor, operation, skillId, versionId, decision, comment);
            latest.put("invocationId", frozen.get("invocationId"));
            if (!json.valueToTree(latest).equals(json.valueToTree(frozen))) throw changed();
        } catch (RuntimeException denied) {
            approvals.finish(actor, approvalId, "FAILED", "PLATFORM_APPROVAL_PRE_DISPATCH_FAILED");
            return refusal(denied);
        }
        ResponseEntity<?> response;
        try { response = governance.change(actor.session(), skillId, versionId, operation, decision, comment, null); }
        catch (RuntimeException failure) {
            var denied = refusal(failure);
            if (denied.getStatusCode().is4xxClientError()) {
                approvals.finish(actor, approvalId, "FAILED", "PLATFORM_APPROVAL_SKILL_REJECTED");
                return denied;
            }
            return unknown(actor, approvalId, skillId, versionId);
        }
        if (response != null && response.getStatusCode().is4xxClientError()) {
            approvals.finish(actor, approvalId, "FAILED", "PLATFORM_APPROVAL_SKILL_REJECTED");
            return refusal(new ResponseStatusException(response.getStatusCode()));
        }
        if (!confirmed(response, operation, versionId, decision)) return unknown(actor, approvalId, skillId, versionId);
        approvals.finish(actor, approvalId, "SUCCEEDED", null);
        return response;
    }

    private Map<String, Object> snapshot(ConnectionIdentity actor, Operation operation, long skillId, long versionId,
                                         String decision, String comment) {
        if (skillId < 1 || versionId < 1) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        if (operation == Operation.REVIEW ? !Set.of("APPROVE", "REJECT").contains(decision) : decision != null || comment != null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        }
        if (comment != null && comment.length() > 2000) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        var response = catalog.get(actor.session(), skillId);
        if (response == null || !response.getStatusCode().is2xxSuccessful()) {
            throw new ResponseStatusException(response == null ? HttpStatus.SERVICE_UNAVAILABLE : response.getStatusCode());
        }
        JsonNode detail = json.valueToTree(response.getBody());
        JsonNode skill = detail.path("skill");
        if (!skill.path("id").canConvertToLong() || skill.path("id").asLong() != skillId) throw changed();
        boolean allowed = operation == Operation.REVIEW ? detail.path("actions").path("canReview").asBoolean(false)
                : detail.path("actions").path("canPublish").asBoolean(false);
        if (!allowed) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        JsonNode version = null;
        for (JsonNode candidate : detail.path("versions")) {
            if (candidate.path("id").canConvertToLong() && candidate.path("id").asLong() == versionId) version = candidate;
        }
        if (version == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        String status = version.path("status").asText();
        boolean isDefault = skill.path("defaultVersionId").canConvertToLong() && skill.path("defaultVersionId").asLong() == versionId;
        if (!FROM.get(operation).contains(status) || operation == Operation.SET_DEFAULT && isDefault) throw changed();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("contractVersion", 1); result.put("operation", operation.name());
        result.put("skillId", skillId); result.put("versionId", versionId);
        for (String field : new String[] {"publisher", "name", "displayName", "visibility", "projectCode", "defaultVersionId", "defaultVersion"}) {
            result.put(field, json.convertValue(skill.get(field), Object.class));
        }
        result.put("isDefault", isDefault);
        for (String field : new String[] {"version", "status", "sourceType", "sourceRef", "sourceSha256", "contentTreeSha256", "hasScripts", "declaredLicense"}) {
            result.put(field, json.convertValue(version.get(field), Object.class));
        }
        result.put("riskReport", json.convertValue(version.get("riskReport"), Object.class));
        result.put("decision", decision); result.put("comment", comment);
        try { return json.readValue(json.writeValueAsBytes(result), MAP); }
        catch (Exception invalid) { throw changed(); }
    }

    private boolean confirmed(ResponseEntity<?> response, Operation operation, long versionId, String decision) {
        if (response == null || !response.getStatusCode().is2xxSuccessful()) return false;
        JsonNode result = json.valueToTree(response.getBody());
        if (result == null || !result.path("id").canConvertToLong() || result.path("id").asLong() != versionId) return false;
        String expected = switch (operation) {
            case REVIEW -> "APPROVE".equals(decision) ? "APPROVED" : "REJECTED";
            case PUBLISH, SET_DEFAULT -> "PUBLISHED";
            case DEPRECATE -> "DEPRECATED";
            case REVOKE -> "REVOKED";
        };
        return expected.equals(result.path("status").asText());
    }

    private static String subject(long skillId, long versionId) { return "SKILL:" + skillId + ":VERSION:" + versionId; }

    private static ResponseStatusException changed() {
        return new ResponseStatusException(HttpStatus.CONFLICT, "Skill version changed");
    }

    private static ResponseEntity<?> refusal(RuntimeException failure) {
        int status = failure instanceof ResponseStatusException denied ? denied.getStatusCode().value()
                : failure instanceof FeignException remote && remote.status() > 0 ? remote.status()
                : failure instanceof IllegalArgumentException || failure instanceof ClassCastException
                        || failure instanceof NullPointerException ? 409 : 503;
        return status == 409 ? ResponseEntity.status(409).body(Map.of("code", STATE_CHANGED)) : ResponseEntity.status(status).build();
    }

    private ResponseEntity<?> unknown(ConnectionIdentity actor, String approvalId, long skillId, long versionId) {
        approvals.finish(actor, approvalId, "UNKNOWN", "PLATFORM_APPROVAL_EXECUTION_UNKNOWN");
        return ResponseEntity.accepted().body(Map.of("status", "UNKNOWN", "approvalId", approvalId, "skillId", skillId, "versionId", versionId,
                "message", "Skill 版本操作结果未确认，请查询原审批并重新读取 Skill 版本；不要新建申请直接重试。"));
    }
}
