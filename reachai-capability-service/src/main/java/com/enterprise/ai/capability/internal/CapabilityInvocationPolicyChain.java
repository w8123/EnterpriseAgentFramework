package com.enterprise.ai.capability.internal;

import com.enterprise.ai.common.capability.CapabilityInvocationFailureCategory;
import com.enterprise.ai.common.capability.CapabilityInvocationRequest;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Locale;
import java.util.Map;

/** Restrictive-only policies shared by every Capability invoker. */
@Component
public class CapabilityInvocationPolicyChain {

    public void validate(CapabilityInvocationRequest request,
                         CapabilityInvocationAsset asset,
                         long nowEpochMs) {
        if (request.deadlineEpochMs() != null && request.deadlineEpochMs() <= nowEpochMs) {
            reject("CAPABILITY_DEADLINE_EXCEEDED", CapabilityInvocationFailureCategory.TIMEOUT,
                    "Capability invocation deadline has already expired");
        }
        if (!asset.enabled()) {
            reject("CAPABILITY_TOOL_DISABLED", CapabilityInvocationFailureCategory.DISABLED,
                    "Capability Tool is disabled: " + asset.qualifiedName());
        }
        Map<String, Object> constraints = request.constraints();
        String expectedQualifiedName = text(constraints.get("expectedQualifiedName"));
        if (expectedQualifiedName != null && !expectedQualifiedName.equals(asset.qualifiedName())) {
            reject("CAPABILITY_POLICY_QUALIFIED_NAME_MISMATCH",
                    CapabilityInvocationFailureCategory.POLICY_REJECTED,
                    "Capability Tool does not match the required qualified name");
        }
        String expectedProjectCode = text(constraints.get("expectedProjectCode"));
        if (expectedProjectCode != null
                && (asset.capabilityCode() == null
                || !expectedProjectCode.equalsIgnoreCase(asset.capabilityCode()))) {
            reject("CAPABILITY_POLICY_PROJECT_MISMATCH",
                    CapabilityInvocationFailureCategory.POLICY_REJECTED,
                    "Capability Tool does not belong to the required project");
        }
        if (truthy(constraints.get("requireUserIdentity"))
                && text(request.context().get("externalUserId")) == null
                && text(request.context().get("globalUserId")) == null) {
            reject("CAPABILITY_IDENTITY_REQUIRED",
                    CapabilityInvocationFailureCategory.IDENTITY_REQUIRED,
                    "Capability Tool requires a current user identity");
        }

        String evalMode = text(request.evaluationPolicy().get("mode"));
        if (evalMode != null && !"NONE".equalsIgnoreCase(evalMode)) {
            String sideEffect = asset.sideEffect() == null
                    ? "" : asset.sideEffect().toUpperCase(Locale.ROOT);
            if (!"READ".equals(sideEffect) && !"READ_ONLY".equals(sideEffect)
                    && !"NONE".equals(sideEffect)) {
                reject("CAPABILITY_EVAL_SIDE_EFFECT_BLOCKED",
                        CapabilityInvocationFailureCategory.POLICY_REJECTED,
                        "Evaluation can invoke only read-only Capability Tools");
            }
        }
    }

    private static void reject(String code,
                               CapabilityInvocationFailureCategory category,
                               String message) {
        throw new CapabilityInvocationPolicyException(code, category, message);
    }

    private static boolean truthy(Object value) {
        if (value instanceof Boolean bool) return bool;
        return value != null && "true".equalsIgnoreCase(String.valueOf(value).trim());
    }

    private static String text(Object value) {
        if (value == null) return null;
        String text = String.valueOf(value).trim();
        return StringUtils.hasText(text) ? text : null;
    }
}
