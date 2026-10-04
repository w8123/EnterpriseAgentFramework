package com.enterprise.ai.control.runtime;

import com.enterprise.ai.common.capability.HttpApiDraftTrialPolicy;
import com.enterprise.ai.common.capability.WorkflowReadOnlyTrialPolicy;
import com.enterprise.ai.common.capability.ConsoleCapabilityInvocationContracts;
import com.enterprise.ai.common.capability.HttpApiFirstCallPolicy;
import com.enterprise.ai.control.capability.CapabilityReviewGateway;
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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Explicit browser action for one saved, read-only business method or API draft. */
@RestController
@RequiredArgsConstructor
public class ControlWorkflowReadOnlyTrialController {
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
        if (body == null || !body.keySet().equals(Set.of("workflowId", "expectedRevision", "inputParams"))) {
            return error(HttpStatus.BAD_REQUEST, "HTTP_API_TRIAL_REQUEST_INVALID", "请提供草稿、保存修订和测试输入");
        }
        String workflowId = text(body.get("workflowId"));
        String revision = text(body.get("expectedRevision"));
        Map<String, Object> input = inputParams(body.get("inputParams"));
        if (workflowId == null || !workflowId.matches("[A-Za-z0-9_-]{1,32}") || revision == null || input == null) {
            return error(HttpStatus.BAD_REQUEST, "HTTP_API_TRIAL_REQUEST_INVALID", "试运行输入无效，请检查后重试");
        }
        ResponseEntity<Object> saved;
        try { saved = runtime.workflowWorkingCopy(workflowId); }
        catch (RuntimeException unavailable) {
            return error(HttpStatus.SERVICE_UNAVAILABLE, "HTTP_API_TRIAL_DRAFT_UNAVAILABLE", "无法读取已保存草稿");
        }
        if (saved == null || !saved.getStatusCode().is2xxSuccessful()
                || !(saved.getBody() instanceof Map<?, ?> workingCopy)) {
            return error(HttpStatus.SERVICE_UNAVAILABLE, "HTTP_API_TRIAL_DRAFT_UNAVAILABLE", "无法读取已保存草稿");
        }
        Long projectId = positive(workingCopy.get("projectId"));
        String projectCode = text(workingCopy.get("projectCode"));
        String savedRevision = text(workingCopy.get("revision"));
        String graph = workingCopy.get("graphSpecJson") instanceof String value && StringUtils.hasText(value)
                ? value : null; // The signed digest binds exact stored bytes, including surrounding whitespace.
        if (projectId == null || projectCode == null || savedRevision == null || graph == null
                || !workflowId.equals(text(workingCopy.get("workflowId")))) {
            return error(HttpStatus.SERVICE_UNAVAILABLE, "HTTP_API_TRIAL_DRAFT_INVALID", "已保存草稿身份无法确认");
        }
        access.requireProject(session, PlatformPermissions.WORKFLOW_DEBUG, projectId, projectCode);
        access.requireProject(session, PlatformPermissions.CAPABILITY_INVOKE, projectId, projectCode);
        if (!"DRAFT".equals(workingCopy.get("status"))) {
            return error(HttpStatus.CONFLICT, "WORKFLOW_TRIAL_DRAFT_REQUIRED", "只读试运行需要已保存且未发布的草稿");
        }
        if (!revision.equals(savedRevision)) {
            return error(HttpStatus.CONFLICT, "HTTP_API_TRIAL_DRAFT_STALE", "草稿已变化，请保存或重新加载后重试");
        }
        WorkflowReadOnlyTrialPolicy.Target target;
        try { target = WorkflowReadOnlyTrialPolicy.target(json, graph); }
        catch (IllegalArgumentException unsupported) {
            return error(HttpStatus.CONFLICT, "HTTP_API_TRIAL_GRAPH_UNSUPPORTED",
                    "本次只支持一个业务方法或 API 接变量的只读草稿，请调整并保存后重试");
        }
        String actor = String.valueOf(session.user().getId());
        if (WorkflowReadOnlyTrialPolicy.BUSINESS_METHOD.equals(target.assetType())) {
            return runBusinessMethod(session, workflowId, revision, graph, projectId, projectCode, actor, target, input);
        }
        ResponseEntity<Object> ownerResponse;
        try { ownerResponse = capability.getHttpApi(target.apiId(), actor); }
        catch (RuntimeException unavailable) {
            return error(HttpStatus.SERVICE_UNAVAILABLE, "HTTP_API_TRIAL_OWNER_UNAVAILABLE", "API 来源暂不可读取");
        }
        if (ownerResponse == null || !ownerResponse.getStatusCode().is2xxSuccessful()
                || !(ownerResponse.getBody() instanceof Map<?, ?> ownerBody)) {
            return error(HttpStatus.SERVICE_UNAVAILABLE, "HTTP_API_TRIAL_OWNER_UNAVAILABLE", "API 来源暂不可读取");
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
                || !target.qualifiedName().equals(qualifiedName) || environment.isBlank()) {
            return error(HttpStatus.CONFLICT, "HTTP_API_TRIAL_OWNER_CHANGED", "API 所属项目或身份已变化");
        }
        if (!summary.path("sourceConfirmed").asBoolean(false)
                || !"ACCEPTED".equals(summary.path("sourceStatus").asText())
                || !Objects.equals(summary.path("candidateContractHash").asText(), contractHash)
                || !contractHash.matches("[0-9a-f]{64}") || !sourceRevision.matches("[0-9a-f]{64}")) {
            return error(HttpStatus.CONFLICT, "HTTP_API_TRIAL_SOURCE_CHANGED", "API 来源或已接纳契约已变化");
        }
        String method = summary.path("httpMethod").asText();
        if (!HttpApiDraftTrialPolicy.readOnlyOwner(method, contract)
                || HttpApiFirstCallPolicy.unsupportedReason(contract) != null) {
            return error(HttpStatus.CONFLICT, "HTTP_API_TRIAL_READ_ONLY_REQUIRED",
                    "此 API 未明确声明为本批支持的只读 GET 操作");
        }
        try {
            if (!ControlToolAclDecisionService.DECISION_ALLOW.equals(acl.decide(session.roles(),
                    projectId, projectCode, "TOOL", qualifiedName))) {
                return error(HttpStatus.FORBIDDEN, "HTTP_API_TRIAL_ACL_DENIED", "当前角色无此 API 的试运行权限");
            }
        } catch (RuntimeException unavailable) {
            return error(HttpStatus.SERVICE_UNAVAILABLE, "HTTP_API_TRIAL_ACL_UNAVAILABLE", "API 调用权限暂不可用");
        }
        WorkflowReadOnlyTrialPolicy.TrialCommand command = new WorkflowReadOnlyTrialPolicy.TrialCommand(
                WorkflowReadOnlyTrialPolicy.VERSION, workflowId, revision,
                HttpApiDraftTrialPolicy.graphSha256(graph), projectId, projectCode, actor,
                List.of(new WorkflowReadOnlyTrialPolicy.AllowedTarget(target.nodeId(), target.apiId(),
                        qualifiedName, environment, contractHash, sourceRevision)), input,
                System.currentTimeMillis() + 45_000);
        return trials.run(command);
    }

    private ResponseEntity<Object> runBusinessMethod(PlatformAuthenticatedSession session, String workflowId,
            String revision, String graph, Long projectId, String projectCode, String actor,
            WorkflowReadOnlyTrialPolicy.Target target, Map<String, Object> input) {
        final ConsoleCapabilityInvocationContracts.InvocationContext owner;
        try {
            var response = capability.getBusinessMethodInvocationContext(target.qualifiedName(), actor);
            if (response == null || !response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
                return error(HttpStatus.SERVICE_UNAVAILABLE, "BUSINESS_METHOD_TRIAL_OWNER_UNAVAILABLE", "业务方法来源暂不可读取");
            }
            owner = json.convertValue(response.getBody(), ConsoleCapabilityInvocationContracts.InvocationContext.class);
        } catch (RuntimeException unavailable) {
            return error(HttpStatus.SERVICE_UNAVAILABLE, "BUSINESS_METHOD_TRIAL_OWNER_UNAVAILABLE", "业务方法来源暂不可读取");
        }
        String rejection = WorkflowReadOnlyTrialPolicy.businessOwnerRejection(owner, projectId, projectCode, target.qualifiedName());
        if (rejection != null) return error(HttpStatus.CONFLICT, rejection,
                "业务方法来源、只读声明或项目测试身份条件已变化，请到方法详情核对");
        try {
            if (!ControlToolAclDecisionService.DECISION_ALLOW.equals(acl.decide(session.roles(),
                    projectId, projectCode, "TOOL", target.qualifiedName()))) {
                return error(HttpStatus.FORBIDDEN, "BUSINESS_METHOD_TRIAL_ACL_DENIED", "当前角色无此业务方法的试运行权限");
            }
        } catch (RuntimeException unavailable) {
            return error(HttpStatus.SERVICE_UNAVAILABLE, "BUSINESS_METHOD_TRIAL_ACL_UNAVAILABLE", "业务方法调用权限暂不可用");
        }
        try { WorkflowReadOnlyTrialPolicy.businessInput(json, graph, target, owner, input); }
        catch (IllegalArgumentException invalid) {
            return error(HttpStatus.CONFLICT, invalid.getMessage(), "请检查业务方法声明、必填输入与参数映射后重新保存");
        }
        return trials.run(new WorkflowReadOnlyTrialPolicy.TrialCommand(WorkflowReadOnlyTrialPolicy.VERSION,
                workflowId, revision, WorkflowReadOnlyTrialPolicy.graphSha256(graph), projectId, projectCode, actor,
                List.of(WorkflowReadOnlyTrialPolicy.AllowedTarget.businessMethod(target, owner)), input,
                System.currentTimeMillis() + 45_000));
    }

    private static Map<String, Object> inputParams(Object raw) {
        if (!(raw instanceof Map<?, ?> values) || values.size() > 16) return null;
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

    private static ResponseEntity<Object> error(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).body(Map.of("success", false,
                "errorCode", code, "errorMessage", message));
    }
}
