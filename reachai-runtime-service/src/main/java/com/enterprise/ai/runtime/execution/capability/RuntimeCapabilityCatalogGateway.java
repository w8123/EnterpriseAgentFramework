package com.enterprise.ai.runtime.execution.capability;

import com.enterprise.ai.runtime.client.capability.RuntimeCapabilityCatalogClient;
import com.enterprise.ai.runtime.client.capability.RuntimeCapabilityCatalogFeignClient;
import com.enterprise.ai.runtime.client.capability.RuntimeCapabilityInternalAuthSigner;

import com.enterprise.ai.common.internalauth.InternalServiceAuthHeaders;
import com.enterprise.ai.common.capability.CapabilityInvocationRequest;
import com.enterprise.ai.common.capability.CapabilityInvocationResponse;
import com.enterprise.ai.common.capability.CapabilityInvocationStatus;
import com.enterprise.ai.common.capability.CapabilityInvocationFailureCategory;
import com.enterprise.ai.common.capability.HttpApiConsoleContracts;
import com.enterprise.ai.common.capability.ConsoleCapabilityInvocationContracts;
import com.enterprise.ai.common.capability.WorkflowReadOnlyTrialPolicy;
import com.enterprise.ai.runtime.identity.WorkflowExecutionIdentity;
import com.enterprise.ai.runtime.execution.policy.RuntimeEvalExecutionContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Serializes once, scrubs caller-controlled identity, then signs the exact bytes sent to Capability.
 */
@Component
public class RuntimeCapabilityCatalogGateway implements RuntimeCapabilityCatalogClient {

    public static final String SIGNED_EVAL_POLICY_FIELD = "evaluationPolicy";

    private static final Set<String> IDENTITY_FIELDS = Set.of(
            "tenantId", "userId", "externalUserId", "globalUserId", "userName",
            "deptId", "deptName", "roles", "attributes");

    private final RuntimeCapabilityCatalogFeignClient transport;
    private final RuntimeCapabilityInternalAuthSigner signer;
    private final ObjectMapper objectMapper;
    private final ThreadLocal<BusinessMethodDraftScope> businessMethodDraft = new ThreadLocal<>();

    private record BusinessMethodDraftScope(WorkflowReadOnlyTrialPolicy.BusinessMethodPin pin,
                                            BooleanSupplier current, AtomicBoolean dispatched) { }

    public RuntimeCapabilityCatalogGateway(RuntimeCapabilityCatalogFeignClient transport,
                                           RuntimeCapabilityInternalAuthSigner signer,
                                           ObjectMapper objectMapper) {
        this.transport = transport;
        this.signer = signer;
        this.objectMapper = objectMapper;
    }

    @Override
    public Map<String, Object> getToolDefinition(String qualifiedName) {
        try { return transport.getToolDefinition(qualifiedName); }
        catch (feign.FeignException missing) {
            if (missing.status() == 404) return null;
            throw missing;
        }
    }

    @Override
    public Map<String, Object> executeTool(String qualifiedName, Map<String, Object> request) {
        return invokeTool(qualifiedName, request).toLegacyMap();
    }

    @Override
    public CapabilityInvocationResponse invokeTool(String qualifiedName, Map<String, Object> request) {
        Map<String, Object> outbound = request == null
                ? new LinkedHashMap<>() : new LinkedHashMap<>(request);
        Object marker = outbound.remove(TRUSTED_IDENTITY_ATTRIBUTE);
        WorkflowExecutionIdentity identity = marker instanceof WorkflowExecutionIdentity trusted
                ? trusted : null;
        Object evalMarker = outbound.remove(TRUSTED_EVAL_CONTEXT_ATTRIBUTE);
        RuntimeEvalExecutionContext evaluation = evalMarker instanceof RuntimeEvalExecutionContext trusted
                ? trusted : RuntimeEvalExecutionContext.none();
        boolean studio = identity != null && identity.source() == WorkflowExecutionIdentity.Source.STUDIO_PROJECT_TEST;
        BusinessMethodDraftScope draft = businessMethodDraft.get();
        Object consoleMarker = outbound.remove(TRUSTED_CONSOLE_INVOCATION_ATTRIBUTE);
        var console = consoleMarker instanceof ConsoleCapabilityInvocationContracts.InvocationCommand command ? command : null;
        if (console != null) {
            if (identity != null || draft != null || evaluation.isEvaluation()
                    || !Objects.equals(console.qualifiedName(), qualifiedName)
                    || !Objects.equals(console.invocationId(), outbound.get("invocationId"))
                    || !Objects.equals(console.input(), stringMap(outbound.get("input")))
                    || !(Set.of("READ", "READ_ONLY", "NONE").contains(console.sideEffect()) || console.confirmedSideEffect())) {
                throw new IllegalStateException("CONSOLE_CAPABILITY_TARGET_CHANGED");
            }
            // Rebuild every constraint from the attested command, never from a caller-supplied map.
            outbound.put("constraints", Map.of("consoleCapabilityInvocation", true,
                    "expectedQualifiedName", console.qualifiedName(), "expectedProjectId", console.projectId(),
                    "expectedProjectCode", console.projectCode(), "expectedContractHash", console.expectedContractHash(),
                    "expectedExecutionRevision", console.expectedExecutionRevision(),
                    "requireSignedInvocation", true));
            outbound.put("deadlineEpochMs", console.deadlineEpochMs());
        }
        if (studio && draft == null) throw new IllegalStateException("BUSINESS_METHOD_TRIAL_SCOPE_REQUIRED");
        // A public map may reduce ordinary execution, but cannot opt into Studio authority.
        Map<String, Object> suppliedConstraints = stringMap(outbound.get("constraints"));
        suppliedConstraints.remove(WorkflowReadOnlyTrialPolicy.STUDIO_CONSTRAINT);
        outbound.put("constraints", suppliedConstraints);
        if (draft != null) {
            var pin = draft.pin();
            if (!studio || evaluation.isEvaluation()
                    || !identity.authorizeProjectCredential(pin.projectId(), pin.projectCode())
                    || !Objects.equals(qualifiedName, pin.owner().qualifiedName())
                    || !Objects.equals(pin.nodeId(), stringMap(outbound.get("context")).get("nodeId"))
                    || !Objects.equals(pin.expectedInput(), stringMap(outbound.get("input")))) {
                throw new IllegalStateException("BUSINESS_METHOD_TRIAL_TARGET_CHANGED");
            }
            if (!draft.current().getAsBoolean() || System.currentTimeMillis() > pin.deadlineEpochMs()) {
                throw new IllegalStateException("BUSINESS_METHOD_TRIAL_DRAFT_STALE");
            }
            var owner = getBusinessMethodExecutionContext(qualifiedName, pin.projectCode());
            String rejected = WorkflowReadOnlyTrialPolicy.businessOwnerRejection(owner,
                    pin.projectId(), pin.projectCode(), qualifiedName);
            if (rejected != null) throw new IllegalStateException(rejected);
            if (!Objects.equals(owner, pin.owner())) throw new IllegalStateException("BUSINESS_METHOD_TRIAL_OWNER_CHANGED");
            if (!draft.current().getAsBoolean() || !draft.dispatched().compareAndSet(false, true)) {
                throw new IllegalStateException("BUSINESS_METHOD_TRIAL_ALREADY_DISPATCHED_OR_STALE");
            }
            outbound.put("constraints", Map.of(WorkflowReadOnlyTrialPolicy.STUDIO_CONSTRAINT, true,
                    "expectedQualifiedName", qualifiedName, "expectedProjectId", pin.projectId(),
                    "expectedProjectCode", pin.projectCode(), "expectedContractHash", owner.acceptedContractHash(),
                    "expectedExecutionRevision", owner.executionRevision(),
                    "requireSignedInvocation", true));
            outbound.put("deadlineEpochMs", pin.deadlineEpochMs());
        }
        if (console == null && (identity == null || identity.source() == WorkflowExecutionIdentity.Source.DEBUG_UNTRUSTED)) {
            // Ordinary Studio debug (including node/session execution) may read owner facts,
            // but it may not bypass the explicit signed method-trial grant. Do not infer
            // the asset type or trusted identity from the caller's node/config/input map.
            Map<String, Object> definition = getToolDefinition(qualifiedName);
            if (definition == null || !WorkflowReadOnlyTrialPolicy.BUSINESS_METHOD.equals(definition.get("assetType"))) {
                var missing = CapabilityInvocationRequest.fromRuntime(qualifiedName, outbound);
                return new CapabilityInvocationResponse(missing.contractVersion(), missing.invocationId(),
                        missing.qualifiedName(), null, null, CapabilityInvocationStatus.REJECTED, false, null,
                        "CAPABILITY_TOOL_NOT_FOUND", "业务方法不存在或尚未接纳", CapabilityInvocationFailureCategory.NOT_FOUND,
                        false, null, null, null, Map.of());
            }
            if (definition != null && WorkflowReadOnlyTrialPolicy.BUSINESS_METHOD.equals(definition.get("assetType"))) {
                throw new IllegalStateException("BUSINESS_METHOD_DEBUG_IDENTITY_DENIED: 请使用显式只读真实试运行");
            }
        }
        // A caller-controlled map must never be able to forge or weaken a signed Eval policy.
        outbound.remove(SIGNED_EVAL_POLICY_FIELD);
        if (evaluation.isEvaluation()) {
            outbound.put(SIGNED_EVAL_POLICY_FIELD, evaluation.toSignedPolicy());
        }

        Map<String, Object> context = stringMap(outbound.get("context"));
        IDENTITY_FIELDS.forEach(context::remove);
        if (console != null) context.clear();

        String source = InternalServiceAuthHeaders.IDENTITY_SOURCE_RUNTIME_UNTRUSTED;
        String tenantId = "";
        String userId = "";
        if (studio) {
            // Only a validated request-local method pin may map projectCode to the signed scope.
            source = InternalServiceAuthHeaders.IDENTITY_SOURCE_RUNTIME_TENANT_TRUSTED;
            tenantId = draft.pin().projectCode();
            context.clear();
            context.put("tenantId", tenantId);
        } else if (identity != null && identity.canResolveUserAcl()) {
            source = InternalServiceAuthHeaders.IDENTITY_SOURCE_RUNTIME_TRUSTED;
            tenantId = normalized(identity.tenantId());
            userId = normalized(identity.userId());
            if (StringUtils.hasText(tenantId)) {
                context.put("tenantId", tenantId);
            }
            context.put("externalUserId", userId);
        } else if (identity != null && identity.canResolveProjectCredential()
                && StringUtils.hasText(identity.tenantId())) {
            source = InternalServiceAuthHeaders.IDENTITY_SOURCE_RUNTIME_TENANT_TRUSTED;
            tenantId = normalized(identity.tenantId());
            context.put("tenantId", tenantId);
        }
        outbound.put("context", context);

        CapabilityInvocationRequest invocation = CapabilityInvocationRequest.fromRuntime(qualifiedName, outbound);
        byte[] exactBody;
        try {
            exactBody = objectMapper.writeValueAsBytes(invocation.toWireMap());
        } catch (Exception serializationFailure) {
            throw new IllegalStateException("Capability Tool request serialization failed", serializationFailure);
        }
        Map<String, String> headers = signer.signInvocation(
                source, tenantId, userId, exactBody);
        final CapabilityInvocationResponse response;
        try { response = transport.invokeCapability(headers, exactBody); }
        catch (RuntimeException uncertain) {
            if (!studio) throw uncertain;
            throw new IllegalStateException("BUSINESS_METHOD_TRIAL_RESULT_UNCONFIRMED");
        }
        if (response == null) {
            throw new IllegalStateException("Capability Tool response is missing");
        }
        validateResponse(invocation, response);
        if (studio) {
            // No transport headers, credentials or arbitrary error strings enter the draft result/state.
            return new CapabilityInvocationResponse(response.contractVersion(), response.invocationId(),
                    response.qualifiedName(), response.toolName(), response.toolTitle(), response.status(), response.success(),
                    studioOutput(response.data()), response.code(), response.success() ? null : "业务方法试运行未确认，请查看 Run/Trace",
                    response.failureCategory(), false, response.latencyMs(), response.attempt(),
                    response.businessCode(), Map.of());
        }
        return response;
    }

    @Override
    public <T> T withBusinessMethodDraftScope(WorkflowReadOnlyTrialPolicy.BusinessMethodPin pin,
                                             BooleanSupplier currentDraft, Supplier<T> execution) {
        if (pin == null || currentDraft == null || execution == null || businessMethodDraft.get() != null) {
            throw new IllegalStateException("BUSINESS_METHOD_TRIAL_SCOPE_INVALID");
        }
        businessMethodDraft.set(new BusinessMethodDraftScope(pin, currentDraft, new AtomicBoolean()));
        try { return execution.get(); }
        finally { businessMethodDraft.remove(); }
    }

    @Override
    public ConsoleCapabilityInvocationContracts.InvocationContext getBusinessMethodExecutionContext(
            String qualifiedName, String projectCode) {
        if (!StringUtils.hasText(projectCode)) throw new IllegalArgumentException("Business method project scope is required");
        byte[] body;
        try {
            body = objectMapper.writeValueAsBytes(Map.of("qualifiedName", qualifiedName,
                    "context", Map.of("tenantId", projectCode.trim())));
        } catch (Exception invalid) { throw new IllegalStateException("Business method owner request cannot be serialized", invalid); }
        var owner = transport.businessMethodExecutionContext(qualifiedName,
                signer.signBusinessMethodExecution(qualifiedName, projectCode.trim(), body), body);
        if (owner == null || owner.contractVersion() != 1 || !qualifiedName.equals(owner.qualifiedName())
                || !projectCode.trim().equals(owner.projectCode())) throw new IllegalStateException("BUSINESS_METHOD_TRIAL_OWNER_CHANGED");
        return owner;
    }

    private Object redact(Object value, String key) {
        if (key != null && key.toLowerCase(java.util.Locale.ROOT)
                .matches(".*(?:password|secret|token|credential|authorization|apikey|api_key|cookie).*")) return "[redacted]";
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> safe = new LinkedHashMap<>();
            map.forEach((name, item) -> safe.put(String.valueOf(name), redact(item, String.valueOf(name))));
            return safe;
        }
        if (value instanceof List<?> items) return items.stream().map(item -> redact(item, key)).toList();
        return value;
    }

    private Map<String, Object> studioOutput(Object value) {
        Map<String, Object> normalized = new LinkedHashMap<>();
        normalized.put("data", redact(value, null));
        return normalized;
    }

    @Override
    public Map<String, Object> getProject(String projectCode) {
        return transport.getProject(projectCode);
    }

    @Override
    public Map<String, Object> getProjectById(Long projectId) {
        return transport.getProjectById(projectId);
    }

    @Override
    public List<Map<String, Object>> listProjectTools(Long projectId) {
        return transport.listProjectTools(projectId);
    }

    @Override
    public Map<String, Object> projectReadinessFacts(Long projectId) {
        return transport.projectReadinessFacts(projectId);
    }

    @Override
    public HttpApiConsoleContracts.ExecutionContext getHttpApiExecutionContext(Long apiId, String projectCode) {
        if (apiId == null || apiId <= 0 || !StringUtils.hasText(projectCode)) {
            throw new IllegalArgumentException("HTTP API owner scope is required");
        }
        byte[] body;
        try {
            body = objectMapper.writeValueAsBytes(Map.of("apiId", apiId,
                    "context", Map.of("tenantId", projectCode.trim())));
        } catch (Exception failure) {
            throw new IllegalStateException("HTTP API owner request cannot be serialized", failure);
        }
        HttpApiConsoleContracts.ExecutionContext context = transport.httpApiExecutionContext(apiId,
                signer.signHttpApiExecution(apiId, projectCode.trim(), body), body);
        if (context == null || context.contractVersion() != HttpApiConsoleContracts.VERSION
                || !apiId.equals(context.apiId()) || !projectCode.trim().equals(context.projectCode())) {
            throw new IllegalStateException("HTTP API owner response is invalid");
        }
        return context;
    }

    private Map<String, Object> stringMap(Object value) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (value instanceof Map<?, ?> raw) {
            raw.forEach((key, item) -> result.put(String.valueOf(key), item));
        }
        return result;
    }

    private String normalized(String value) {
        return value == null ? "" : value.trim();
    }

    private void validateResponse(CapabilityInvocationRequest request,
                                  CapabilityInvocationResponse response) {
        if (!request.invocationId().equals(response.invocationId())
                || !request.qualifiedName().equals(response.qualifiedName())) {
            throw new IllegalStateException("Capability Tool response correlation is invalid");
        }
        boolean succeeded = response.status()
                == com.enterprise.ai.common.capability.CapabilityInvocationStatus.SUCCEEDED;
        if (succeeded != response.success()) {
            throw new IllegalStateException("Capability Tool response status is inconsistent");
        }
        if (response.retryable()
                && response.status()
                != com.enterprise.ai.common.capability.CapabilityInvocationStatus.TECHNICAL_FAILED) {
            throw new IllegalStateException("Capability Tool response retry policy is invalid");
        }
    }
}
