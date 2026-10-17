package com.enterprise.ai.control.runtime;

import com.enterprise.ai.common.capability.ConsoleCapabilityInvocationContracts;
import com.enterprise.ai.common.capability.HttpApiDraftTrialPolicy;
import com.enterprise.ai.common.capability.HttpApiFirstCallPolicy;
import com.enterprise.ai.common.capability.WorkflowReadOnlyTrialPolicy;
import com.enterprise.ai.control.capability.CapabilityReviewGateway;
import com.enterprise.ai.control.capability.CatalogReadAuthorization;
import com.enterprise.ai.control.client.runtime.RuntimeProxyClient;
import com.enterprise.ai.control.governance.ControlToolAclDecisionService;
import com.enterprise.ai.control.identity.PlatformAuthenticatedSession;
import com.enterprise.ai.control.identity.PlatformPermissions;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Explicit browser action: run the whole saved working copy for real when every business method / HTTP API node
 * is read-only (D28). Control attests the session, project grants, per-target owner facts and Tool ACL, then signs
 * one command; the browser never supplies a graph, target, hash, identity or credential.
 */
@RestController
@RequiredArgsConstructor
public class ControlWorkflowReadOnlyTrialController {
    private static final Set<String> REQUIRED_FIELDS = Set.of("workflowId", "expectedRevision", "inputParams");
    private static final Set<String> ALLOWED_FIELDS = Set.of("workflowId", "expectedRevision", "inputParams", "message");

    private final RuntimeProxyClient runtime;
    private final CapabilityReviewGateway capability;
    private final RuntimeManagementAccess access;
    private final ControlToolAclDecisionService acl;
    private final ControlWorkflowReadOnlyTrialGateway trials;
    private final ObjectMapper json;

    @PostMapping("/api/workflows/studio/read-only-trials")
    public ResponseEntity<Object> run(@RequestBody(required = false) Map<String, Object> body) {
        PlatformAuthenticatedSession session = access.require(PlatformPermissions.WORKFLOW_DEBUG);
        access.require(PlatformPermissions.CAPABILITY_INVOKE);
        if (body == null || !body.keySet().containsAll(REQUIRED_FIELDS) || !ALLOWED_FIELDS.containsAll(body.keySet())) {
            return error(HttpStatus.BAD_REQUEST, "WORKFLOW_TRIAL_REQUEST_INVALID", "请提供草稿、保存修订和测试输入");
        }
        String workflowId = text(body.get("workflowId"));
        String revision = text(body.get("expectedRevision"));
        Map<String, Object> input = inputParams(body.get("inputParams"));
        Object rawMessage = body.get("message");
        String message = rawMessage instanceof String value && StringUtils.hasText(value) ? value : null;
        if (workflowId == null || !workflowId.matches("[A-Za-z0-9_-]{1,32}") || revision == null || input == null
                || rawMessage != null && !(rawMessage instanceof String)
                || message != null && message.length() > WorkflowReadOnlyTrialPolicy.MAX_MESSAGE_LENGTH) {
            return error(HttpStatus.BAD_REQUEST, "WORKFLOW_TRIAL_REQUEST_INVALID", "试运行输入无效，请检查后重试");
        }
        ResponseEntity<Object> saved;
        try { saved = runtime.workflowWorkingCopy(workflowId); }
        catch (RuntimeException unavailable) {
            return error(HttpStatus.SERVICE_UNAVAILABLE, "WORKFLOW_TRIAL_DRAFT_UNAVAILABLE", "无法读取已保存草稿");
        }
        if (saved == null || !saved.getStatusCode().is2xxSuccessful()
                || !(saved.getBody() instanceof Map<?, ?> workingCopy)) {
            return error(HttpStatus.SERVICE_UNAVAILABLE, "WORKFLOW_TRIAL_DRAFT_UNAVAILABLE", "无法读取已保存草稿");
        }
        Long projectId = positive(workingCopy.get("projectId"));
        String projectCode = text(workingCopy.get("projectCode"));
        String savedRevision = text(workingCopy.get("revision"));
        String graph = workingCopy.get("graphSpecJson") instanceof String value && StringUtils.hasText(value)
                ? value : null; // The signed digest binds exact stored bytes, including surrounding whitespace.
        if (projectId == null || projectCode == null || savedRevision == null || graph == null
                || !workflowId.equals(text(workingCopy.get("workflowId")))) {
            return error(HttpStatus.SERVICE_UNAVAILABLE, "WORKFLOW_TRIAL_DRAFT_INVALID", "已保存草稿身份无法确认");
        }
        access.requireProject(session, PlatformPermissions.WORKFLOW_DEBUG, projectId, projectCode);
        access.requireProject(session, PlatformPermissions.CAPABILITY_INVOKE, projectId, projectCode);
        // No status gate: the trial runs the saved working copy, never the published version, so an edited
        // working copy of a published Workflow can be trialled before re-publishing (D28).
        if (!revision.equals(savedRevision)) {
            return error(HttpStatus.CONFLICT, "WORKFLOW_TRIAL_DRAFT_STALE", "草稿已变化，请保存或重新加载后重试");
        }
        WorkflowReadOnlyTrialPolicy.Plan plan;
        try { plan = WorkflowReadOnlyTrialPolicy.plan(json, graph); }
        catch (WorkflowReadOnlyTrialPolicy.GraphRejected rejected) {
            Map<String, Object> response = new LinkedHashMap<>(errorBody(rejected.code(), rejected.userMessage()));
            response.put("blockedNodes", rejected.blocked().stream().map(node -> Map.of(
                    "nodeId", node.nodeId(), "nodeName", node.nodeName(), "nodeType", node.nodeType(),
                    "reason", node.reason())).toList());
            return ResponseEntity.status(HttpStatus.CONFLICT).body(response);
        }
        String actor = String.valueOf(session.user().getId());
        List<WorkflowReadOnlyTrialPolicy.AllowedTarget> allowed = new ArrayList<>();
        for (WorkflowReadOnlyTrialPolicy.Target target : plan.targets()) {
            Approval approval = WorkflowReadOnlyTrialPolicy.BUSINESS_METHOD.equals(target.assetType())
                    ? approveBusinessMethod(session, actor, projectId, projectCode, target)
                    : approveHttpApi(session, actor, projectId, projectCode, target);
            if (approval.rejection() != null) return approval.rejection();
            allowed.add(approval.target());
        }
        return trials.run(new WorkflowReadOnlyTrialPolicy.TrialCommand(WorkflowReadOnlyTrialPolicy.VERSION,
                workflowId, revision, HttpApiDraftTrialPolicy.graphSha256(graph), projectId, projectCode, actor,
                List.copyOf(allowed), message, input,
                System.currentTimeMillis() + WorkflowReadOnlyTrialPolicy.MAX_DURATION_MS));
    }

    private record Approval(WorkflowReadOnlyTrialPolicy.AllowedTarget target, ResponseEntity<Object> rejection) {
        static Approval rejected(ResponseEntity<Object> response) { return new Approval(null, response); }
    }

    private Approval approveHttpApi(PlatformAuthenticatedSession session, String actor, Long projectId,
                                    String projectCode, WorkflowReadOnlyTrialPolicy.Target target) {
        String node = "「" + target.nodeName() + "」";
        ResponseEntity<Object> ownerResponse;
        try { ownerResponse = capability.getHttpApi(target.apiId(), actor,
                CatalogReadAuthorization.resourceBody(session, PlatformPermissions.CAPABILITY_INVOKE)); }
        catch (RuntimeException unavailable) { ownerResponse = null; }
        if (ownerResponse == null || !ownerResponse.getStatusCode().is2xxSuccessful()
                || !(ownerResponse.getBody() instanceof Map<?, ?> ownerBody)) {
            return Approval.rejected(nodeError(HttpStatus.SERVICE_UNAVAILABLE, "HTTP_API_TRIAL_OWNER_UNAVAILABLE",
                    node + "的 API 来源暂不可读取", target));
        }
        JsonNode owner = json.valueToTree(ownerBody);
        JsonNode summary = owner.path("summary");
        JsonNode contract = owner.path("acceptedContract");
        String qualifiedName = summary.path("qualifiedName").asText();
        String environment = summary.path("environment").asText();
        String contractHash = summary.path("acceptedContractHash").asText();
        String sourceRevision = summary.path("sourceSetRevision").asText();
        if (summary.path("id").asLong() != target.apiId()
                || summary.path("projectId").asLong() != projectId
                || !projectCode.equals(summary.path("projectCode").asText())
                || !target.qualifiedName().equals(qualifiedName) || environment.isBlank()
                || !CatalogReadAuthorization.matchesOwner(ownerResponse, projectId, projectCode)) {
            return Approval.rejected(nodeError(HttpStatus.CONFLICT, "HTTP_API_TRIAL_OWNER_CHANGED",
                    node + "的 API 所属项目或身份已变化，请重新选择 API 并保存", target));
        }
        if (!summary.path("sourceConfirmed").asBoolean(false)
                || !"ACCEPTED".equals(summary.path("sourceStatus").asText())
                || !Objects.equals(summary.path("candidateContractHash").asText(), contractHash)
                || !contractHash.matches("[0-9a-f]{64}") || !sourceRevision.matches("[0-9a-f]{64}")) {
            return Approval.rejected(nodeError(HttpStatus.CONFLICT, "HTTP_API_TRIAL_SOURCE_CHANGED",
                    WorkflowReadOnlyTrialPolicy.ownerRejectionMessage("HTTP_API_TRIAL_SOURCE_CHANGED", target.nodeName()), target));
        }
        if (!HttpApiDraftTrialPolicy.readOnlyOwner(summary.path("httpMethod").asText(), contract)
                || HttpApiFirstCallPolicy.unsupportedReason(contract) != null) {
            return Approval.rejected(nodeError(HttpStatus.CONFLICT, "HTTP_API_TRIAL_READ_ONLY_REQUIRED",
                    WorkflowReadOnlyTrialPolicy.ownerRejectionMessage("HTTP_API_TRIAL_READ_ONLY_REQUIRED", target.nodeName()), target));
        }
        ResponseEntity<Object> denied = aclDenied(session, projectId, projectCode, qualifiedName, target,
                "HTTP_API_TRIAL_ACL_DENIED", "HTTP_API_TRIAL_ACL_UNAVAILABLE", "API");
        if (denied != null) return Approval.rejected(denied);
        return new Approval(WorkflowReadOnlyTrialPolicy.AllowedTarget.httpApi(target, environment, contractHash,
                sourceRevision), null);
    }

    private Approval approveBusinessMethod(PlatformAuthenticatedSession session, String actor, Long projectId,
                                           String projectCode, WorkflowReadOnlyTrialPolicy.Target target) {
        ConsoleCapabilityInvocationContracts.InvocationContext owner = null;
        try {
            var response = capability.getBusinessMethodInvocationContext(target.qualifiedName(), actor);
            if (response != null && response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                owner = json.convertValue(response.getBody(), ConsoleCapabilityInvocationContracts.InvocationContext.class);
            }
        } catch (RuntimeException unavailable) {
            owner = null;
        }
        if (owner == null) {
            return Approval.rejected(nodeError(HttpStatus.SERVICE_UNAVAILABLE, "BUSINESS_METHOD_TRIAL_OWNER_UNAVAILABLE",
                    "「" + target.nodeName() + "」的业务方法来源暂不可读取", target));
        }
        String rejection = WorkflowReadOnlyTrialPolicy.businessOwnerRejection(owner, projectId, projectCode, target.qualifiedName());
        if (rejection != null) {
            return Approval.rejected(nodeError(HttpStatus.CONFLICT, rejection,
                    WorkflowReadOnlyTrialPolicy.ownerRejectionMessage(rejection, target.nodeName()), target));
        }
        ResponseEntity<Object> denied = aclDenied(session, projectId, projectCode, target.qualifiedName(), target,
                "BUSINESS_METHOD_TRIAL_ACL_DENIED", "BUSINESS_METHOD_TRIAL_ACL_UNAVAILABLE", "业务方法");
        if (denied != null) return Approval.rejected(denied);
        return new Approval(WorkflowReadOnlyTrialPolicy.AllowedTarget.businessMethod(target, owner), null);
    }

    private ResponseEntity<Object> aclDenied(PlatformAuthenticatedSession session, Long projectId, String projectCode,
                                             String qualifiedName, WorkflowReadOnlyTrialPolicy.Target target,
                                             String deniedCode, String unavailableCode, String label) {
        try {
            if (!ControlToolAclDecisionService.DECISION_ALLOW.equals(acl.decide(session.roles(),
                    projectId, projectCode, "TOOL", qualifiedName))) {
                return nodeError(HttpStatus.FORBIDDEN, deniedCode,
                        "当前角色无「" + target.nodeName() + "」所用" + label + "的调用权限（ACL）", target);
            }
            return null;
        } catch (RuntimeException unavailable) {
            return nodeError(HttpStatus.SERVICE_UNAVAILABLE, unavailableCode, label + "调用权限暂不可用", target);
        }
    }

    private static ResponseEntity<Object> nodeError(HttpStatus status, String code, String message,
                                                    WorkflowReadOnlyTrialPolicy.Target target) {
        Map<String, Object> body = new LinkedHashMap<>(errorBody(code, message));
        body.put("failedNodeId", target.nodeId());
        body.put("failedNodeName", target.nodeName());
        return ResponseEntity.status(status).body(body);
    }

    private static Map<String, Object> inputParams(Object raw) {
        if (!(raw instanceof Map<?, ?> values) || values.size() > WorkflowReadOnlyTrialPolicy.MAX_INPUT_FIELDS) return null;
        Map<String, Object> safe = new LinkedHashMap<>();
        for (var entry : values.entrySet()) {
            if (!(entry.getKey() instanceof String name)
                    || !name.matches("[A-Za-z][A-Za-z0-9_.-]{0,127}")) return null;
            Object value = entry.getValue();
            if (value != null && !(value instanceof String || value instanceof Number || value instanceof Boolean)) return null;
            if (value instanceof String text && text.length() > 2048) return null;
            safe.put(name, value);
        }
        return safe;
    }

    private static Long positive(Object raw) {
        if (!(raw instanceof Number number) || number.longValue() <= 0
                || number.doubleValue() != number.longValue()) return null;
        return number.longValue();
    }

    private static String text(Object raw) {
        return raw instanceof String value && StringUtils.hasText(value) ? value.trim() : null;
    }

    private static Map<String, Object> errorBody(String code, String message) {
        return Map.of("success", false, "errorCode", code, "errorMessage", message);
    }

    private static ResponseEntity<Object> error(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).body(errorBody(code, message));
    }
}
