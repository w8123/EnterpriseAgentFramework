package com.enterprise.ai.control.capability;

import com.enterprise.ai.common.capability.HttpApiConsoleContracts;
import com.enterprise.ai.control.governance.ControlToolAclDecisionService;
import com.enterprise.ai.control.identity.PlatformAuthenticatedSession;
import com.enterprise.ai.control.identity.PlatformPermissions;
import com.enterprise.ai.control.identity.PlatformRequestAuthorization;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Platform-session facade: Capability owns the API and Runtime owns its project connection and calls. */
@RestController
@RequiredArgsConstructor
public class HttpApiConsoleController {
    private static final String PROJECT = "PROJECT";
    private final CapabilityReviewGateway capability;
    private final RuntimeHttpApiConsoleGateway runtime;
    private final PlatformRequestAuthorization authorization;
    private final ControlToolAclDecisionService acl;
    private final ObjectMapper json;

    @GetMapping("/api/apis")
    public ResponseEntity<Object> list(HttpServletRequest request,
                                       @RequestParam Long projectId,
                                       @RequestParam(required = false) String environment,
                                       @RequestParam(required = false) String keyword,
                                       @RequestParam(required = false) String method,
                                       @RequestParam(required = false) String sourceStatus,
                                       @RequestParam(defaultValue = "1") int current,
                                       @RequestParam(defaultValue = "20") int size) {
        PlatformAuthenticatedSession session = authorization.requirePermission(request, PlatformPermissions.PLATFORM_READ);
        if (projectId == null || projectId <= 0 || current <= 0 || size <= 0 || size > 100) {
            return error(HttpStatus.BAD_REQUEST, "HTTP_API_LIST_INVALID", "项目或分页条件无效");
        }
        ProjectResolution project = project(projectId, actor(session));
        if (project.failure() != null) return project.failure();
        authorization.requireResourcePermission(request, PlatformPermissions.PLATFORM_READ,
                PROJECT, null, project.projectCode());
        ResponseEntity<Object> page = forward(capability.listHttpApis(projectId, environment, keyword, method,
                sourceStatus, current, size, actor(session)));
        if (!page.getStatusCode().is2xxSuccessful()) return page;
        return catalogWithRuntimeState(page, projectId, project.projectCode(), actor(session));
    }

    @GetMapping("/api/apis/{id}")
    public ResponseEntity<Object> detail(HttpServletRequest request, @PathVariable long id) {
        PlatformAuthenticatedSession session = authorization.requirePermission(request, PlatformPermissions.PLATFORM_READ);
        OwnerResolution resolved = owner(id, actor(session));
        if (resolved.failure() != null) return resolved.failure();
        authorizeProject(request, PlatformPermissions.PLATFORM_READ, resolved.owner());
        return resolved.response();
    }

    @PostMapping("/api/apis/{id}/accept")
    public ResponseEntity<Object> accept(HttpServletRequest request, @PathVariable long id,
                                         @RequestBody(required = false) Map<String, Object> body) {
        PlatformAuthenticatedSession session = authorization.requirePermission(request, PlatformPermissions.PLATFORM_WRITE);
        if (body == null || !body.keySet().equals(Set.of("expectedSourceSetRevision"))
                || !(body.get("expectedSourceSetRevision") instanceof String revision)
                || !revision.matches("[a-f0-9]{64}")) {
            return error(HttpStatus.BAD_REQUEST, "HTTP_API_ACCEPT_INVALID", "接纳修订无效");
        }
        OwnerResolution resolved = owner(id, actor(session));
        if (resolved.failure() != null) return resolved.failure();
        authorizeProject(request, PlatformPermissions.PLATFORM_WRITE, resolved.owner());
        return forward(capability.acceptHttpApi(id, revision, actor(session)));
    }

    @GetMapping("/api/apis/{id}/connection")
    public ResponseEntity<Object> connection(HttpServletRequest request, @PathVariable long id) {
        PlatformAuthenticatedSession session = authorization.requirePermission(request, PlatformPermissions.PLATFORM_READ);
        OwnerResolution resolved = owner(id, actor(session));
        if (resolved.failure() != null) return resolved.failure();
        authorizeProject(request, PlatformPermissions.PLATFORM_READ, resolved.owner());
        try {
            return forward(runtime.readConnection(command(resolved.owner(), null), actor(session)));
        } catch (RuntimeException unavailable) {
            return error(HttpStatus.SERVICE_UNAVAILABLE, "HTTP_API_RUNTIME_UNAVAILABLE", "连接查询服务暂不可用");
        }
    }

    @PutMapping("/api/apis/{id}/connection")
    public ResponseEntity<Object> saveConnection(HttpServletRequest request, @PathVariable long id,
                                                  @RequestBody(required = false) Map<String, Object> body) {
        PlatformAuthenticatedSession session = authorization.requirePermission(request, PlatformPermissions.PLATFORM_WRITE);
        HttpApiConsoleContracts.ConnectionSaveRequest save;
        try { save = HttpApiConsoleContracts.ConnectionSaveRequest.fromWire(body); }
        catch (IllegalArgumentException invalid) {
            return error(HttpStatus.BAD_REQUEST, "HTTP_API_CONNECTION_INVALID", "连接配置格式无效");
        }
        OwnerResolution resolved = owner(id, actor(session));
        if (resolved.failure() != null) return resolved.failure();
        authorizeProject(request, PlatformPermissions.PLATFORM_WRITE, resolved.owner());
        try {
            return forward(runtime.saveConnection(command(resolved.owner(), save), actor(session)));
        } catch (RuntimeException unavailable) {
            return error(HttpStatus.SERVICE_UNAVAILABLE, "HTTP_API_RUNTIME_UNAVAILABLE", "连接保存服务暂不可用");
        }
    }

    @PostMapping("/api/apis/{id}/invocations")
    public ResponseEntity<Object> invoke(HttpServletRequest request, @PathVariable long id,
                                         @RequestBody(required = false) Map<String, Object> body) {
        HttpApiConsoleContracts.PublicInvocationRequest input;
        try { input = HttpApiConsoleContracts.PublicInvocationRequest.fromWire(body); }
        catch (IllegalArgumentException invalid) {
            return error(HttpStatus.BAD_REQUEST, "HTTP_API_INVOCATION_INVALID", "试调用请求格式无效");
        }
        PlatformAuthenticatedSession session = authorization.requirePermission(request, PlatformPermissions.PLATFORM_READ);
        authorization.requirePermission(request, PlatformPermissions.CAPABILITY_INVOKE);
        OwnerResolution resolved = owner(id, actor(session));
        if (resolved.failure() != null) return resolved.failure();
        Owner owner = resolved.owner();
        ResponseEntity<Object> denied = invokePermission(request, session, owner.projectId(),
                owner.projectCode(), owner.qualifiedName());
        if (denied != null) return denied;
        if (!owner.sourceConfirmed() || !"ACCEPTED".equals(owner.sourceStatus())
                || !Objects.equals(owner.candidateHash(), owner.acceptedHash())
                || !Objects.equals(owner.acceptedHash(), input.expectedContractHash())
                || (input.expectedSourceSetRevision() != null
                    && !Objects.equals(owner.sourceSetRevision(), input.expectedSourceSetRevision()))) {
            return error(HttpStatus.CONFLICT, "HTTP_API_CONTRACT_CHANGED", "API 来源或契约已变化，请刷新后重试");
        }
        if ("WRITE".equals(owner.sideEffect()) && (!input.confirmedSideEffect()
                || !Objects.equals(owner.sourceSetRevision(), input.expectedSourceSetRevision()))) {
            return error(HttpStatus.CONFLICT, "HTTP_API_CONFIRMATION_REQUIRED", "写 API 需要确认当前来源、连接与输入后提交");
        }
        HttpApiConsoleContracts.InvocationCommand command = new HttpApiConsoleContracts.InvocationCommand(
                HttpApiConsoleContracts.VERSION, input.invocationId(), actor(session), id,
                owner.qualifiedName(), owner.projectId(), owner.projectCode(), owner.environment(),
                owner.acceptedHash(), owner.sourceSetRevision(), input.connectionRevision(),
                input.pathParams(), input.queryParams(), System.currentTimeMillis() + 45_000,
                input.body(), input.confirmedSideEffect(), input.expectedCredentialRevision());
        try {
            ResponseEntity<Object> response = runtime.invoke(command, actor(session));
            if (response == null || !response.getStatusCode().is2xxSuccessful()) return forward(response);
            HttpApiConsoleContracts.InvocationOutcome result = outcome(response.getBody());
            if (!matches(result, command)) return unconfirmed(input.invocationId());
            return response;
        } catch (RuntimeException outcomeUnconfirmed) {
            return unconfirmed(input.invocationId());
        }
    }

    @GetMapping("/api/api-invocations/{invocationId}")
    public ResponseEntity<Object> getInvocation(HttpServletRequest request, @PathVariable String invocationId,
                                                @RequestParam String projectCode) {
        PlatformAuthenticatedSession session = authorization.requirePermission(request, PlatformPermissions.PLATFORM_READ);
        authorization.requirePermission(request, PlatformPermissions.CAPABILITY_INVOKE);
        if (!StringUtils.hasText(projectCode) || !projectCode.matches("[A-Za-z0-9._:-]{1,96}")) {
            return error(HttpStatus.BAD_REQUEST, "HTTP_API_PROJECT_INVALID", "项目编码无效");
        }
        authorization.requireResourcePermission(request, PlatformPermissions.PLATFORM_READ, PROJECT, null, projectCode);
        authorization.requireResourcePermission(request, PlatformPermissions.CAPABILITY_INVOKE, PROJECT, null, projectCode);
        ResponseEntity<Object> response;
        try { response = runtime.getInvocation(invocationId, projectCode, actor(session)); }
        catch (IllegalArgumentException invalid) { return ResponseEntity.notFound().build(); }
        catch (RuntimeException unavailable) {
            return error(HttpStatus.SERVICE_UNAVAILABLE, "HTTP_API_RUNTIME_UNAVAILABLE", "调用结果查询暂不可用");
        }
        if (response == null || response.getStatusCode().value() == 404) return ResponseEntity.notFound().build();
        if (!response.getStatusCode().is2xxSuccessful()) return forward(response);
        HttpApiConsoleContracts.InvocationOutcome result = outcome(response.getBody());
        if (result == null || !"HTTP_API".equals(result.targetType())
                || !invocationId.equals(result.invocationId())
                || !projectCode.equals(result.projectCode()) || result.projectId() == null
                || !StringUtils.hasText(result.qualifiedName())) {
            return error(HttpStatus.BAD_GATEWAY, "HTTP_API_RUNTIME_RESPONSE_INVALID", "调用记录无效");
        }
        ResponseEntity<Object> denied = invokePermission(request, session, result.projectId(),
                result.projectCode(), result.qualifiedName());
        return denied == null ? response : denied;
    }

    private OwnerResolution owner(long id, String actor) {
        if (id <= 0) return new OwnerResolution(null, null,
                error(HttpStatus.BAD_REQUEST, "HTTP_API_ID_INVALID", "API 标识无效"));
        ResponseEntity<Object> response = capability.getHttpApi(id, actor);
        if (response == null || response.getStatusCode().value() == 404) {
            return new OwnerResolution(null, null, ResponseEntity.notFound().build());
        }
        if (!response.getStatusCode().is2xxSuccessful() || !(response.getBody() instanceof Map<?, ?> body)
                || !(body.get("summary") instanceof Map<?, ?> summary)) {
            return new OwnerResolution(null, null,
                    error(HttpStatus.SERVICE_UNAVAILABLE, "HTTP_API_OWNER_UNAVAILABLE", "API owner 暂不可用"));
        }
        Long returnedId = positive(summary.get("id"));
        Long projectId = positive(summary.get("projectId"));
        String projectCode = text(summary.get("projectCode"));
        String qualifiedName = text(summary.get("qualifiedName"));
        String environment = text(summary.get("environment"));
        if (!Objects.equals(returnedId, id) || projectId == null || projectCode == null
                || qualifiedName == null || environment == null) {
            return new OwnerResolution(null, null,
                    error(HttpStatus.BAD_GATEWAY, "HTTP_API_OWNER_INVALID", "API owner 返回无效身份"));
        }
        ProjectResolution project = project(projectId, actor);
        if (project.failure() != null) return new OwnerResolution(null, null, project.failure());
        if (!projectCode.equals(project.projectCode())) return new OwnerResolution(null, null,
                error(HttpStatus.CONFLICT, "HTTP_API_PROJECT_CHANGED", "API 所属项目已变化"));
        Owner owner = new Owner(id, projectId, projectCode, qualifiedName, environment,
                Boolean.TRUE.equals(summary.get("sourceConfirmed")),
                text(summary.get("sourceStatus")), text(summary.get("candidateContractHash")),
                text(summary.get("acceptedContractHash")), text(summary.get("sourceSetRevision")),
                body.get("acceptedContract") instanceof Map<?, ?> contract ? text(contract.get("sideEffect")) : null);
        return new OwnerResolution(owner, response, null);
    }

    private ResponseEntity<Object> catalogWithRuntimeState(ResponseEntity<Object> response, long projectId,
                                                             String projectCode, String actor) {
        Map<String, Object> page;
        try { page = json.convertValue(response.getBody(), new TypeReference<LinkedHashMap<String, Object>>() { }); }
        catch (IllegalArgumentException invalid) {
            return error(HttpStatus.BAD_GATEWAY, "HTTP_API_CATALOG_INVALID", "API 目录返回格式无效");
        }
        if (page == null || !(page.get("records") instanceof List<?> records) || records.size() > 100) {
            return error(HttpStatus.BAD_GATEWAY, "HTTP_API_CATALOG_INVALID", "API 目录返回格式无效");
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        List<String> names = new ArrayList<>();
        for (Object raw : records) {
            if (!(raw instanceof Map<?, ?> item) || !Objects.equals(positive(item.get("projectId")), projectId)
                    || !Objects.equals(text(item.get("projectCode")), projectCode)
                    || positive(item.get("id")) == null || text(item.get("qualifiedName")) == null) {
                return error(HttpStatus.BAD_GATEWAY, "HTTP_API_CATALOG_INVALID", "API 目录项目归属无效");
            }
            String name = text(item.get("qualifiedName"));
            if (name.length() > 200) return error(HttpStatus.BAD_GATEWAY,
                    "HTTP_API_CATALOG_INVALID", "API 目录身份无效");
            names.add(name);
            rows.add(json.convertValue(item, new TypeReference<LinkedHashMap<String, Object>>() { }));
        }
        if (names.isEmpty()) return response;
        if (new HashSet<>(names).size() != names.size()) return error(HttpStatus.BAD_GATEWAY,
                "HTTP_API_CATALOG_INVALID", "API 目录身份重复");
        Map<String, Map<String, Object>> stateByName = new HashMap<>();
        try {
            ResponseEntity<Object> states = runtime.catalogStates(new HttpApiConsoleContracts.CatalogStatesRequest(
                    HttpApiConsoleContracts.VERSION, projectId, projectCode, names), actor);
            if (states != null && states.getStatusCode().is2xxSuccessful()
                    && states.getBody() instanceof List<?> entries) {
                for (Object raw : entries) {
                    Map<String, Object> entry = json.convertValue(raw,
                            new TypeReference<LinkedHashMap<String, Object>>() { });
                    String name = text(entry.get("qualifiedName"));
                    if (name == null || !names.contains(name) || stateByName.putIfAbsent(name, entry) != null) {
                        throw new IllegalArgumentException("Runtime returned an invalid catalog identity");
                    }
                }
                if (stateByName.size() != names.size()) stateByName.clear();
            }
        } catch (RuntimeException unavailable) {
            stateByName.clear();
        }
        for (Map<String, Object> row : rows) {
            Map<String, Object> state = stateByName.get(text(row.get("qualifiedName")));
            row.put("connectionStatus", state == null ? "UNAVAILABLE" : state.get("connectionStatus"));
            row.put("latestInvocationStatus", state == null ? null : state.get("latestInvocationStatus"));
            row.put("latestHttpStatus", state == null ? null : state.get("latestHttpStatus"));
            row.put("latestInvocationAt", state == null ? null : state.get("latestInvocationAt"));
        }
        page.put("records", rows);
        return ResponseEntity.ok(page);
    }

    private ProjectResolution project(long id, String actor) {
        ResponseEntity<Object> response = capability.getProjectById(id, actor);
        if (response == null || !response.getStatusCode().is2xxSuccessful()
                || !(response.getBody() instanceof Map<?, ?> body)
                || !Objects.equals(positive(body.get("projectId")), id)
                || !StringUtils.hasText(text(body.get("projectCode")))) {
            return new ProjectResolution(null,
                    error(HttpStatus.BAD_GATEWAY, "HTTP_API_PROJECT_UNCONFIRMED", "项目归属无法确认"));
        }
        return new ProjectResolution(text(body.get("projectCode")), null);
    }

    private void authorizeProject(HttpServletRequest request, String permission, Owner owner) {
        authorization.requireResourcePermission(request, permission, PROJECT, null, owner.projectCode());
    }

    private ResponseEntity<Object> invokePermission(HttpServletRequest request, PlatformAuthenticatedSession session,
                                                    Long projectId, String projectCode, String qualifiedName) {
        authorization.requireResourcePermission(request, PlatformPermissions.PLATFORM_READ, PROJECT, null, projectCode);
        authorization.requireResourcePermission(request, PlatformPermissions.CAPABILITY_INVOKE, PROJECT, null, projectCode);
        try {
            String decision = acl.decide(session.roles(), projectId, projectCode, "TOOL", qualifiedName);
            if (!ControlToolAclDecisionService.DECISION_ALLOW.equals(decision)) {
                return error(HttpStatus.FORBIDDEN, "HTTP_API_ACL_DENIED", "当前角色无此 API 的试调用权限");
            }
            return null;
        } catch (RuntimeException unavailable) {
            return error(HttpStatus.SERVICE_UNAVAILABLE, "HTTP_API_ACL_UNAVAILABLE", "调用权限暂不可用");
        }
    }

    private HttpApiConsoleContracts.ConnectionCommand command(Owner owner,
            HttpApiConsoleContracts.ConnectionSaveRequest save) {
        return new HttpApiConsoleContracts.ConnectionCommand(HttpApiConsoleContracts.VERSION,
                owner.id(), owner.qualifiedName(), owner.projectId(), owner.projectCode(), owner.environment(), save);
    }

    private HttpApiConsoleContracts.InvocationOutcome outcome(Object value) {
        try { return json.convertValue(value, HttpApiConsoleContracts.InvocationOutcome.class); }
        catch (IllegalArgumentException invalid) { return null; }
    }

    private boolean matches(HttpApiConsoleContracts.InvocationOutcome result,
                            HttpApiConsoleContracts.InvocationCommand command) {
        return result != null && result.contractVersion() == HttpApiConsoleContracts.VERSION
                && "HTTP_API".equals(result.targetType())
                && Objects.equals(result.invocationId(), command.invocationId())
                && Objects.equals(result.qualifiedName(), command.qualifiedName())
                && Objects.equals(result.projectId(), command.projectId())
                && Objects.equals(result.projectCode(), command.projectCode())
                && Objects.equals(result.environment(), command.environment())
                && Objects.equals(result.expectedContractHash(), command.expectedContractHash())
                && Objects.equals(result.sourceSetRevision(), command.expectedSourceSetRevision())
                && Objects.equals(result.connectionRevision(), command.connectionRevision());
    }

    private ResponseEntity<Object> unconfirmed(String invocationId) {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(Map.of("success", false,
                "code", "HTTP_API_OUTCOME_UNCONFIRMED", "invocationId", invocationId,
                "status", "UNKNOWN", "terminal", false, "queryable", true,
                "message", "本次结果尚未确认，请用同一调用 ID 查询，不要重试 HTTP 请求"));
    }

    private ResponseEntity<Object> forward(ResponseEntity<Object> response) {
        if (response == null) return error(HttpStatus.SERVICE_UNAVAILABLE, "HTTP_API_SERVICE_UNAVAILABLE", "服务暂不可用");
        int status = response.getStatusCode().value();
        if (status == 400 || status == 404 || status == 409) return response;
        if (response.getStatusCode().is2xxSuccessful()) return response;
        return error(HttpStatus.SERVICE_UNAVAILABLE, "HTTP_API_SERVICE_UNAVAILABLE", "服务暂不可用");
    }

    private String actor(PlatformAuthenticatedSession session) {
        if (session == null || session.user() == null || session.user().getId() == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "platform session userId required");
        }
        return String.valueOf(session.user().getId());
    }

    private Long positive(Object value) {
        return value instanceof Number number && number.longValue() > 0 ? number.longValue() : null;
    }

    private String text(Object value) {
        return value instanceof String source && StringUtils.hasText(source) ? source.trim() : null;
    }

    private ResponseEntity<Object> error(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).body(Map.of("success", false, "code", code, "message", message));
    }

    private record Owner(long id, Long projectId, String projectCode, String qualifiedName,
                         String environment, boolean sourceConfirmed, String sourceStatus,
                         String candidateHash, String acceptedHash, String sourceSetRevision, String sideEffect) { }
    private record OwnerResolution(Owner owner, ResponseEntity<Object> response, ResponseEntity<Object> failure) { }
    private record ProjectResolution(String projectCode, ResponseEntity<Object> failure) { }
}
