package com.enterprise.ai.control.mcp.application.platform;

import com.enterprise.ai.control.identity.PlatformAiApprovalService;
import com.enterprise.ai.control.identity.PlatformAiConnectionService.ConnectionIdentity;
import com.enterprise.ai.control.mcp.application.port.McpPlatformAgentAuthoringPort;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import feign.FeignException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import java.util.LinkedHashMap;
import java.util.Map;

/** One owner-reviewed publication of an exact draft and the Workflow versions it will activate. */
@Service
@RequiredArgsConstructor
public class McpPlatformAgentReleaseService {
    private final McpPlatformAgentAuthoringPort authoring;
    private final PlatformAiApprovalService approvals;
    private final ObjectMapper json;

    public ResponseEntity<?> request(ConnectionIdentity actor, String agentId, Long versionId, String revision, String invocationId) {
        ResponseEntity<?> response = authoring.publicationContext(actor.session(), agentId, versionId, revision);
        if (response == null) return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
        if (!response.getStatusCode().is2xxSuccessful()) return response;
        Map<String, Object> frozen = snapshot(response.getBody(), agentId, versionId, revision);
        frozen.put("invocationId", invocationId);
        return ResponseEntity.ok(approvals.prepare(actor, PlatformAiApprovalService.AGENT_PUBLISH, "AGENT:" + agentId, frozen));
    }

    public ResponseEntity<?> execute(ConnectionIdentity actor, String approvalId) {
        var claim = approvals.claim(actor, approvalId, PlatformAiApprovalService.AGENT_PUBLISH);
        Map<String, Object> frozen = claim.arguments();
        String agentId;
        String revision;
        Long versionId;
        Map<String, Long> pins;
        try {
            agentId = text(frozen.get("agentId")); revision = text(frozen.get("baseRevision"));
            versionId = number(frozen.get("configVersionId")); pins = pins(frozen.get("workflowVersions"));
            if (!("AGENT:" + agentId).equals(claim.subject())) throw changed();
            ResponseEntity<?> current = authoring.publicationContext(actor.session(), agentId, versionId, revision);
            if (current == null || !current.getStatusCode().is2xxSuccessful()) {
                throw new ResponseStatusException(current == null ? HttpStatus.SERVICE_UNAVAILABLE : current.getStatusCode());
            }
            Map<String, Object> latest = snapshot(current.getBody(), agentId, versionId, revision);
            if (!java.util.Objects.equals(numberOrNull(latest.get("projectId")), numberOrNull(frozen.get("projectId")))
                    || !java.util.Objects.equals(latest.get("projectCode"), frozen.get("projectCode"))
                    || !java.util.Objects.equals(numberOrNull(latest.get("activeConfigVersionId")), numberOrNull(frozen.get("activeConfigVersionId")))
                    || !pins.equals(pins(latest.get("workflowVersions")))) throw changed();
        } catch (RuntimeException refusal) {
            return failedBeforeDispatch(actor, approvalId, refusalResponse(refusal));
        }

        ResponseEntity<?> response;
        try {
            response = authoring.publishApproved(actor.session(), agentId, versionId, revision,
                    numberOrNull(frozen.get("projectId")), (String) frozen.get("projectCode"), pins);
        } catch (RuntimeException uncertain) {
            ResponseEntity<?> refusal = refusalResponse(uncertain);
            if (refusal.getStatusCode().is4xxClientError()) {
                approvals.finish(actor, approvalId, "FAILED", "PLATFORM_APPROVAL_PUBLISH_REJECTED");
                return refusal;
            }
            return unknown(actor, approvalId, frozen);
        }
        if (response != null && response.getStatusCode().is4xxClientError()) {
            approvals.finish(actor, approvalId, "FAILED", "PLATFORM_APPROVAL_PUBLISH_REJECTED");
            return response;
        }
        if (!confirmed(response, frozen, agentId, versionId)) return unknown(actor, approvalId, frozen);
        approvals.finish(actor, approvalId, "SUCCEEDED", null);
        return response;
    }

    private static boolean confirmed(ResponseEntity<?> response, Map<String, Object> frozen, String agentId, Long versionId) {
        try {
            return response != null && response.getStatusCode().is2xxSuccessful()
                    && response.getBody() instanceof Map<?, ?> result && agentId.equals(result.get("agentId"))
                    && java.util.Objects.equals(frozen.get("projectCode"), result.get("projectCode"))
                    && java.util.Objects.equals(numberOrNull(frozen.get("projectId")), numberOrNull(result.get("projectId")))
                    && result.get("base") instanceof Map<?, ?> config && agentId.equals(config.get("agentId"))
                    && versionId.equals(numberOrNull(config.get("id"))) && "ACTIVE".equals(config.get("status"));
        } catch (RuntimeException invalid) { return false; }
    }

    private ResponseEntity<?> failedBeforeDispatch(ConnectionIdentity actor, String id, ResponseEntity<?> refusal) {
        approvals.finish(actor, id, "FAILED", "PLATFORM_APPROVAL_PRE_DISPATCH_FAILED");
        return refusal == null ? ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build() : refusal;
    }

    private ResponseEntity<?> unknown(ConnectionIdentity actor, String id, Map<String, Object> frozen) {
        approvals.finish(actor, id, "UNKNOWN", "PLATFORM_APPROVAL_EXECUTION_UNKNOWN");
        return ResponseEntity.accepted().body(Map.of("status", "UNKNOWN", "approvalId", id,
                "agentId", frozen.get("agentId"), "configVersionId", frozen.get("configVersionId"),
                "message", "发布结果未确认；请查询原审批和 Agent 配置版本，勿新建请求直接重试。"));
    }

    private Map<String, Object> snapshot(Object body, String agentId, Long versionId, String revision) {
        if (!(body instanceof Map<?, ?> current) || !agentId.equals(current.get("agentId"))
                || revision == null || !revision.matches("[0-9a-f]{64}") || !revision.equals(current.get("revision"))
                || !(current.get("base") instanceof Map<?, ?> base) || !versionId.equals(numberOrNull(base.get("id")))
                || !agentId.equals(base.get("agentId")) || !"DRAFT".equals(base.get("status"))
                || !current.containsKey("projectId") || !current.containsKey("projectCode")
                || !current.containsKey("activeConfigVersionId") || !(current.get("identity") instanceof Map<?, ?> identity)) throw changed();
        Long projectId = numberOrNull(current.get("projectId"));
        Object projectCode = current.get("projectCode");
        if ((projectId == null) != (projectCode == null)
                || projectCode != null && (!(projectCode instanceof String code) || code.isBlank())) throw changed();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("agentId", agentId); result.put("configVersionId", versionId); result.put("baseRevision", revision);
        result.put("projectId", projectId); result.put("projectCode", projectCode);
        result.put("activeConfigVersionId", numberOrNull(current.get("activeConfigVersionId")));
        result.put("workflowVersions", pins(current.get("workflowVersions")));
        result.put("snapshot", json.convertValue(base, new TypeReference<Map<String, Object>>() { }));
        result.put("identity", json.convertValue(identity, new TypeReference<Map<String, Object>>() { }));
        return result;
    }

    private static Map<String, Long> pins(Object value) {
        if (!(value instanceof Map<?, ?> source)) throw changed();
        Map<String, Long> result = new LinkedHashMap<>();
        source.forEach((key, id) -> {
            if (!(key instanceof String workflow) || !workflow.matches("[A-Za-z0-9_-]{1,32}")) throw changed();
            result.put(workflow, number(id));
        });
        return result;
    }

    private static Long numberOrNull(Object value) { return value == null ? null : number(value); }
    private static Long number(Object value) {
        if (!(value instanceof Number)) throw changed();
        try { long id = Long.parseLong(value.toString()); if (id > 0) return id; }
        catch (NumberFormatException invalid) { /* Refuse truncated IDs. */ }
        throw changed();
    }
    private static String text(Object value) { if (value instanceof String text && !text.isBlank()) return text; throw changed(); }
    private static ResponseStatusException changed() { return new ResponseStatusException(HttpStatus.CONFLICT, "Agent publication changed"); }
    private static ResponseEntity<?> refusalResponse(RuntimeException failure) {
        int status = failure instanceof ResponseStatusException denied ? denied.getStatusCode().value()
                : failure instanceof FeignException remote && remote.status() > 0 ? remote.status() : 503;
        return ResponseEntity.status(status).body(Map.of("code", status == 409 ? "AGENT_DRAFT_CHANGED" : "PLATFORM_PUBLISH_REJECTED"));
    }
}
