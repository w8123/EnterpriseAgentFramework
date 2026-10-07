package com.enterprise.ai.control.capability;

import com.enterprise.ai.common.capability.ConsoleCapabilityInvocationContracts;
import com.enterprise.ai.control.governance.ControlToolAclDecisionService;
import com.enterprise.ai.control.identity.PlatformAuthenticatedSession;
import com.enterprise.ai.control.identity.PlatformPermissions;
import com.enterprise.ai.control.identity.PlatformRequestAuthorization;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Console-only public facade for trial calls. Its body contract deliberately has no URL,
 * project, actor, role, credential or SDK identity fields; all target facts are owner-derived.
 */
@RestController
@RequiredArgsConstructor
public class CapabilityInvocationConsoleController {

    private static final String PROJECT_SCOPE = "PROJECT";
    private static final long DEFAULT_DEADLINE_MILLIS = 30_000L;
    private static final long MAX_DEADLINE_MILLIS = 60_000L;
    private final CapabilityReviewGateway capabilityGateway;
    private final RuntimeConsoleCapabilityInvocationGateway runtimeGateway;
    private final PlatformRequestAuthorization authorization;
    private final ControlToolAclDecisionService toolAcl;
    private final ObjectMapper objectMapper;

    @GetMapping("/api/business-methods/{name}/invocation-context")
    public ResponseEntity<Object> context(HttpServletRequest request, @PathVariable String name) {
        PlatformAuthenticatedSession session = requireInvocationPermission(request);
        String actor = actor(session);
        ContextResolution context = readContext(name, actor);
        if (context.failure() != null) return context.failure();
        ResponseEntity<Object> scoped = requireInvocationScopeAndAcl(request, session, context.context());
        return scoped == null ? ResponseEntity.ok(context.context()) : scoped;
    }

    @PostMapping("/api/business-methods/{name}/invocations")
    public ResponseEntity<Object> invoke(HttpServletRequest request,
                                          @PathVariable String name,
                                          @RequestBody(required = false) Map<String, Object> body) {
        final ConsoleCapabilityInvocationContracts.PublicInvocationRequest publicRequest;
        try {
            publicRequest = ConsoleCapabilityInvocationContracts.PublicInvocationRequest.fromWire(body);
        } catch (IllegalArgumentException invalid) {
            return error(HttpStatus.BAD_REQUEST, "CONSOLE_CAPABILITY_REQUEST_INVALID", "试调用请求格式无效");
        }
        PlatformAuthenticatedSession session = requireInvocationPermission(request);
        String actor = actor(session);
        ContextResolution context = readContext(name, actor);
        if (context.failure() != null) return context.failure();
        ResponseEntity<Object> scoped = requireInvocationScopeAndAcl(request, session, context.context());
        if (scoped != null) return scoped;
        ResponseEntity<Object> usable = requireUsableContext(context.context(), publicRequest.expectedContractHash(),
                publicRequest.expectedExecutionRevision());
        if (usable != null) return usable;
        if (requiresConfirmation(context.context().sideEffect()) && !publicRequest.confirmedSideEffect()) {
            return error(HttpStatus.CONFLICT, "CONSOLE_CAPABILITY_CONFIRMATION_REQUIRED", "该业务方法含写入副作用，需明确确认后才可调用");
        }
        ConsoleCapabilityInvocationContracts.InvocationCommand command =
                new ConsoleCapabilityInvocationContracts.InvocationCommand(
                        ConsoleCapabilityInvocationContracts.CONTRACT_VERSION,
                        publicRequest.invocationId(), actor, context.context().projectId(), context.context().projectCode(),
                        context.context().qualifiedName(), context.context().currentContractHash(), context.context().executionRevision(), publicRequest.input(),
                        sensitiveNames(context.context().parameters()), sideEffect(context.context().sideEffect()),
                        publicRequest.confirmedSideEffect(), System.currentTimeMillis() + deadlineMillis(context.context()));
        try {
            return forwardInvocationOutcome(runtimeGateway.invoke(command, actor), publicRequest.invocationId(), context.context());
        } catch (RuntimeException outcomeUnconfirmed) {
            // Runtime may have committed the idempotency claim before the Control-side response was
            // lost. Do not invite a resend: the same UUID is now a read-only investigation handle.
            return outcomeUnconfirmed(publicRequest.invocationId());
        }
    }

    @GetMapping("/api/business-method-invocations/{invocationId}")
    public ResponseEntity<Object> get(HttpServletRequest request, @PathVariable String invocationId) {
        PlatformAuthenticatedSession session = requireInvocationPermission(request);
        String actor = actor(session);
        final ResponseEntity<Object> response;
        try {
            response = runtimeGateway.get(invocationId, actor);
        } catch (RuntimeException unavailable) {
            return error(HttpStatus.SERVICE_UNAVAILABLE, "RUNTIME_CONSOLE_CAPABILITY_UNAVAILABLE", "试调用查询服务暂不可用");
        }
        if (response == null || response.getStatusCode().value() == 404) return ResponseEntity.notFound().build();
        if (!response.getStatusCode().is2xxSuccessful()) return forwardRuntime(response);
        if (!(response.getBody() instanceof Map<?, ?> body) || !validQueryOutcome(body, invocationId)) {
            return error(HttpStatus.BAD_GATEWAY, "CONSOLE_CAPABILITY_RUNTIME_RESPONSE_INVALID", "Runtime 返回的调用记录无效");
        }
        Long projectId = positive(body.get("projectId"));
        String projectCode = text(body.get("projectCode"));
        String qualifiedName = text(body.get("qualifiedName"));
        if (projectId == null || !StringUtils.hasText(projectCode) || !StringUtils.hasText(qualifiedName)) {
            return error(HttpStatus.BAD_GATEWAY, "CONSOLE_CAPABILITY_RUNTIME_RESPONSE_INVALID", "Runtime 返回的调用记录无效");
        }
        ResponseEntity<Object> scoped = requireProjectScopeAndAcl(request, session, projectId, projectCode, qualifiedName);
        return scoped == null ? response : scoped;
    }

    private PlatformAuthenticatedSession requireInvocationPermission(HttpServletRequest request) {
        authorization.requirePermission(request, PlatformPermissions.PLATFORM_READ);
        return authorization.requirePermission(request, PlatformPermissions.CAPABILITY_INVOKE);
    }

    private ContextResolution readContext(String name, String actor) {
        if (!StringUtils.hasText(name)) {
            return new ContextResolution(null, error(HttpStatus.BAD_REQUEST, "CAPABILITY_NAME_REQUIRED", "业务方法名称不能为空"));
        }
        ResponseEntity<Object> response = capabilityGateway.getBusinessMethodInvocationContext(name.trim(), actor);
        if (response == null || response.getStatusCode().value() == 404) {
            return new ContextResolution(null, ResponseEntity.notFound().build());
        }
        if (!response.getStatusCode().is2xxSuccessful()) {
            return new ContextResolution(null, error(HttpStatus.SERVICE_UNAVAILABLE,
                    "CAPABILITY_SERVICE_UNAVAILABLE", "业务方法上下文暂不可用"));
        }
        try {
            ConsoleCapabilityInvocationContracts.InvocationContext context = objectMapper.convertValue(
                    response.getBody(), ConsoleCapabilityInvocationContracts.InvocationContext.class);
            if (context.contractVersion() != ConsoleCapabilityInvocationContracts.CONTRACT_VERSION
                    || !"BUSINESS_METHOD".equals(context.assetType())
                    || !name.trim().equals(context.name()) && !name.trim().equals(context.qualifiedName())
                    || context.projectId() == null || !StringUtils.hasText(context.projectCode())
                    || !StringUtils.hasText(context.qualifiedName())) {
                return new ContextResolution(null, error(HttpStatus.BAD_GATEWAY,
                        "CAPABILITY_INVOCATION_CONTEXT_INVALID", "业务方法上下文无效"));
            }
            return new ContextResolution(context, null);
        } catch (IllegalArgumentException invalid) {
            return new ContextResolution(null, error(HttpStatus.BAD_GATEWAY,
                    "CAPABILITY_INVOCATION_CONTEXT_INVALID", "业务方法上下文无效"));
        }
    }

    private ResponseEntity<Object> requireInvocationScopeAndAcl(HttpServletRequest request,
                                                                  PlatformAuthenticatedSession session,
                                                                  ConsoleCapabilityInvocationContracts.InvocationContext context) {
        return requireProjectScopeAndAcl(request, session, context.projectId(), context.projectCode(), context.qualifiedName());
    }

    private ResponseEntity<Object> requireProjectScopeAndAcl(HttpServletRequest request,
                                                              PlatformAuthenticatedSession session,
                                                              Long projectId,
                                                              String projectCode,
                                                              String qualifiedName) {
        authorization.requireResourcePermission(request, PlatformPermissions.PLATFORM_READ,
                PROJECT_SCOPE, null, projectCode);
        authorization.requireResourcePermission(request, PlatformPermissions.CAPABILITY_INVOKE,
                PROJECT_SCOPE, null, projectCode);
        final String decision;
        try {
            // The ACL projection is still a Tool-protocol decision. BUSINESS_METHOD is the
            // catalog asset type, not a second ACL target kind, so do not create a bypassing
            // namespace that existing precise Tool allow rules cannot match.
            decision = toolAcl.decide(session.roles(), projectId, projectCode, "TOOL", qualifiedName);
        } catch (RuntimeException unavailable) {
            return error(HttpStatus.SERVICE_UNAVAILABLE, "CONTROL_TOOL_ACL_UNAVAILABLE", "调用权限暂不可用");
        }
        if (!ControlToolAclDecisionService.DECISION_ALLOW.equals(decision)) {
            return error(HttpStatus.FORBIDDEN, "CONSOLE_CAPABILITY_ACL_DENIED", "当前平台角色无该业务方法调用权限");
        }
        return null;
    }

    private ResponseEntity<Object> requireUsableContext(
            ConsoleCapabilityInvocationContracts.InvocationContext context,
            String expectedContractHash, String expectedExecutionRevision) {
        if (!context.executable()) {
            return error(HttpStatus.CONFLICT, safeCode(context.blockingCode(), "CONSOLE_CAPABILITY_NOT_EXECUTABLE"),
                    "业务方法当前不可试调用");
        }
        if (!context.enabled() || !context.credentialAvailable() || context.businessIdentityRequired()
                || !"READY".equals(context.sourceAvailability())
                || !safeEquals(context.currentContractHash(), context.acceptedContractHash())
                || !safeEquals(context.currentContractHash(), context.sourceContractHash())) {
            return error(HttpStatus.CONFLICT, "CONSOLE_CAPABILITY_NOT_EXECUTABLE", "业务方法当前不可试调用");
        }
        if (!safeEquals(context.currentContractHash(), expectedContractHash)) {
            return error(HttpStatus.CONFLICT, "CAPABILITY_PUBLISHED_CONTRACT_CHANGED", "业务方法契约已变化，请刷新后重试");
        }
        if (context.executionRevision() == null || !safeEquals(context.executionRevision(), expectedExecutionRevision)) {
            return error(HttpStatus.CONFLICT, "BUSINESS_METHOD_EXECUTION_BINDING_CHANGED", "业务方法连接或凭据已变化，请刷新条件并重新确认");
        }
        return null;
    }

    private ResponseEntity<Object> forwardRuntime(ResponseEntity<Object> response) {
        if (response != null && response.getStatusCode().is2xxSuccessful()) return response;
        if (response != null && response.getStatusCode().value() == 404) return ResponseEntity.notFound().build();
        if (response != null && response.getStatusCode().value() == 409) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(response.getBody());
        }
        if (response != null && response.getStatusCode().value() == 400) {
            return ResponseEntity.badRequest().body(response.getBody());
        }
        return error(HttpStatus.SERVICE_UNAVAILABLE, "RUNTIME_CONSOLE_CAPABILITY_UNAVAILABLE", "试调用执行服务暂不可用");
    }

    /** A broken Runtime response after dispatch is a query handle, never a browser-visible foreign record. */
    private ResponseEntity<Object> forwardInvocationOutcome(ResponseEntity<Object> response,
                                                             String invocationId,
                                                             ConsoleCapabilityInvocationContracts.InvocationContext context) {
        if (response == null || !response.getStatusCode().is2xxSuccessful()) return forwardRuntime(response);
        if (!(response.getBody() instanceof Map<?, ?> body) || !validInvocationOutcome(body, invocationId, context)) {
            return outcomeUnconfirmed(invocationId);
        }
        return ResponseEntity.status(response.getStatusCode()).body(body);
    }

    private boolean validInvocationOutcome(Map<?, ?> body,
                                           String invocationId,
                                           ConsoleCapabilityInvocationContracts.InvocationContext context) {
        return validQueryOutcome(body, invocationId)
                && safeEquals(context.projectId(), positive(body.get("projectId")))
                && safeEquals(context.projectCode(), text(body.get("projectCode")))
                && safeEquals(context.qualifiedName(), text(body.get("qualifiedName")));
    }

    private boolean validQueryOutcome(Map<?, ?> body, String invocationId) {
        Object version = body.get("contractVersion");
        if (!(version instanceof Number number) || number.intValue() != ConsoleCapabilityInvocationContracts.CONTRACT_VERSION) {
            return false;
        }
        String returnedId = text(body.get("invocationId"));
        if (!safeEquals(returnedId, invocationId)) return false;
        Long projectId = positive(body.get("projectId"));
        String projectCode = text(body.get("projectCode"));
        String qualifiedName = text(body.get("qualifiedName"));
        Long runId = positive(body.get("runId"));
        String traceId = text(body.get("traceId"));
        String identityMode = text(body.get("identityMode"));
        String status = text(body.get("status"));
        String dispatchStage = text(body.get("dispatchStage"));
        Object terminal = body.get("terminal");
        return projectId != null && runId != null && StringUtils.hasText(projectCode)
                && StringUtils.hasText(qualifiedName) && StringUtils.hasText(traceId)
                && "PROJECT_CREDENTIAL_NO_BUSINESS_IDENTITY".equals(identityMode)
                && terminal instanceof Boolean terminalValue
                && validLifecycle(status, dispatchStage, terminalValue);
    }

    private boolean validLifecycle(String status, String stage, boolean terminal) {
        if (!StringUtils.hasText(status) || !StringUtils.hasText(stage)) return false;
        return switch (status) {
            // PERSISTED is the durable, pre-dispatch fence written by Runtime before it can
            // atomically transition the row to DISPATCHING. It is a valid nonterminal public
            // state, not a synonym for a completed acceptance response.
            case "ACCEPTED" -> "PERSISTED".equals(stage) && !terminal;
            case "DISPATCHING" -> "DISPATCHING".equals(stage) && !terminal;
            case "SUCCEEDED", "BUSINESS_FAILED" -> "CONFIRMED".equals(stage) && terminal;
            case "NOT_DISPATCHED" -> "NOT_DISPATCHED".equals(stage) && terminal;
            case "UNKNOWN" -> "UNCONFIRMED".equals(stage) && terminal;
            default -> false;
        };
    }

    private ResponseEntity<Object> outcomeUnconfirmed(String invocationId) {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(Map.of(
                "success", false,
                "code", "CONSOLE_CAPABILITY_OUTCOME_UNCONFIRMED",
                "message", "本次调用结果尚未确认，请使用同一 invocationId 查询，勿重复提交",
                "invocationId", invocationId,
                "status", "UNKNOWN",
                "terminal", false,
                "queryable", true));
    }

    private List<String> sensitiveNames(List<ConsoleCapabilityInvocationContracts.Parameter> parameters) {
        Set<String> result = new LinkedHashSet<>();
        collectSensitive(parameters, "", result);
        if (singleDtoRoot(parameters)) {
            // A one-DTO declaration accepts both {request:{...}} and the flat {...} form.
            // Reuse the same recursive path grammar for the flat aliases so nested objects
            // and arrays are redacted by Runtime as well.
            collectSensitive(parameters.get(0).children(), "", result);
        }
        return List.copyOf(result);
    }

    private boolean singleDtoRoot(List<ConsoleCapabilityInvocationContracts.Parameter> parameters) {
        return parameters != null && parameters.size() == 1
                && "object".equalsIgnoreCase(parameters.get(0).type())
                && parameters.get(0).children() != null && !parameters.get(0).children().isEmpty();
    }

    private void collectSensitive(List<ConsoleCapabilityInvocationContracts.Parameter> parameters,
                                  String parentPath,
                                  Set<String> result) {
        if (parameters == null) return;
        for (ConsoleCapabilityInvocationContracts.Parameter parameter : parameters) {
            String path = StringUtils.hasText(parentPath) ? parentPath + "." + parameter.name() : parameter.name();
            Object sensitive = parameter.metadata() == null ? null : parameter.metadata().get("sensitive");
            if (Boolean.TRUE.equals(sensitive) || "true".equalsIgnoreCase(String.valueOf(sensitive))) {
                result.add(path);
            }
            String childPath = "array".equalsIgnoreCase(parameter.type()) ? path + "[]" : path;
            collectSensitive(parameter.children(), childPath, result);
        }
    }

    private long deadlineMillis(ConsoleCapabilityInvocationContracts.InvocationContext context) {
        long source = context == null ? 0L : context.timeoutMs();
        return source <= 0L ? DEFAULT_DEADLINE_MILLIS : Math.min(MAX_DEADLINE_MILLIS, source);
    }

    private boolean requiresConfirmation(String sideEffect) {
        String value = sideEffect(sideEffect);
        return !"NONE".equals(value) && !"READ".equals(value) && !"READ_ONLY".equals(value);
    }

    private String sideEffect(String value) {
        return StringUtils.hasText(value) ? value.trim().toUpperCase(java.util.Locale.ROOT) : "WRITE";
    }

    private String actor(PlatformAuthenticatedSession session) {
        if (session == null || session.user() == null || session.user().getId() == null) {
            throw new org.springframework.web.server.ResponseStatusException(HttpStatus.UNAUTHORIZED,
                    "platform session userId is required");
        }
        return String.valueOf(session.user().getId());
    }

    private Long positive(Object value) {
        if (value instanceof BigInteger number && number.signum() > 0
                && number.compareTo(BigInteger.valueOf(Long.MAX_VALUE)) <= 0) return number.longValue();
        if (value instanceof Number number) return number.longValue() > 0 ? number.longValue() : null;
        return null;
    }

    private String text(Object value) {
        return value == null || !StringUtils.hasText(String.valueOf(value)) ? null : String.valueOf(value).trim();
    }

    private boolean safeEquals(Object left, Object right) { return java.util.Objects.equals(left, right); }

    private String safeCode(String code, String fallback) {
        return StringUtils.hasText(code) && code.trim().matches("[A-Za-z0-9_.:-]{1,128}") ? code.trim() : fallback;
    }

    private ResponseEntity<Object> error(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).body(Map.of("success", false, "code", code, "message", message));
    }

    private record ContextResolution(ConsoleCapabilityInvocationContracts.InvocationContext context,
                                     ResponseEntity<Object> failure) { }
}
