package com.enterprise.ai.capability.internal;

import com.enterprise.ai.common.capability.CapabilityInvocationFailureCategory;
import com.enterprise.ai.common.capability.CapabilityInvocationRequest;
import com.enterprise.ai.common.capability.CapabilityInvocationResponse;
import com.enterprise.ai.common.capability.CapabilityInvocationStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** Typed Capability application boundary: resolve -> policy -> invoke -> classify -> audit-safe result. */
@Service
public class CapabilityInvocationApplicationService {

    private final CapabilityInvocationAssetResolver assetResolver;
    private final CapabilityInvocationPolicyChain policyChain;
    private final CapabilityInvokerRegistry invokerRegistry;

    public CapabilityInvocationApplicationService(CapabilityInvocationAssetResolver assetResolver,
                                                  CapabilityInvocationPolicyChain policyChain,
                                                  CapabilityInvokerRegistry invokerRegistry) {
        this.assetResolver = assetResolver;
        this.policyChain = policyChain;
        this.invokerRegistry = invokerRegistry;
    }

    public CapabilityInvocationResponse invoke(CapabilityInvocationRequest request) {
        long startedAt = System.currentTimeMillis();
        CapabilityInvocationAsset asset = null;
        try {
            asset = assetResolver.require(request.qualifiedName());
            policyChain.validate(request, asset, startedAt);
            Map<String, Object> invoked = invokerRegistry.invoke(asset, request);
            Map<String, Object> correlated = new LinkedHashMap<>(invoked == null ? Map.of() : invoked);
            correlated.putIfAbsent("toolName", asset.name());
            correlated.putIfAbsent("toolTitle", asset.title());
            correlated.put("latencyMs", elapsedSince(startedAt));
            correlated.putIfAbsent("attempt", 1);
            CapabilityInvocationResponse response = CapabilityInvocationResponse.fromLegacy(request, correlated);
            if (response.status() == CapabilityInvocationStatus.TECHNICAL_FAILED
                    && response.failureCategory() == CapabilityInvocationFailureCategory.INTERNAL
                    && Boolean.FALSE.equals(correlated.get("success"))) {
                return failed(request, asset, text(correlated.get("code"), "CAPABILITY_RESPONSE_INVALID"),
                        text(correlated.get("message"), "Capability invoker returned an invalid response"),
                        CapabilityInvocationFailureCategory.RESPONSE_INVALID, false, startedAt);
            }
            return response;
        } catch (CapabilityInvocationPolicyException rejected) {
            return failed(request, asset, rejected.code(), rejected.getMessage(),
                    rejected.category(), false, startedAt, rejected.safeMetadata());
        } catch (IllegalStateException rejected) {
            if (failureText(rejected.getMessage()).contains("deadline")) {
                return failed(request, asset, "CAPABILITY_DEADLINE_EXCEEDED", rejected.getMessage(),
                        CapabilityInvocationFailureCategory.TIMEOUT, false, startedAt);
            }
            CapabilityInvocationFailureCategory category = rejectedCategory(rejected.getMessage());
            return failed(request, asset, "CAPABILITY_TOOL_EXECUTION_REJECTED", rejected.getMessage(),
                    category, false, startedAt);
        } catch (RuntimeException failure) {
            CapabilityInvocationFailureCategory category = technicalCategory(failure);
            boolean retryable = category == CapabilityInvocationFailureCategory.TIMEOUT
                    || category == CapabilityInvocationFailureCategory.CONNECTIVITY;
            return failed(request, asset, "CAPABILITY_TOOL_EXECUTION_FAILED", failure.getMessage(),
                    category, retryable, startedAt);
        }
    }

    private CapabilityInvocationResponse failed(CapabilityInvocationRequest request,
                                                CapabilityInvocationAsset asset,
                                                String code,
                                                String message,
                                                CapabilityInvocationFailureCategory category,
                                                boolean retryable,
                                                long startedAt) {
        return failed(request, asset, code, message, category, retryable, startedAt, Map.of());
    }

    private CapabilityInvocationResponse failed(CapabilityInvocationRequest request,
                                                CapabilityInvocationAsset asset,
                                                String code,
                                                String message,
                                                CapabilityInvocationFailureCategory category,
                                                boolean retryable,
                                                long startedAt,
                                                Map<String, Object> safeMetadata) {
        CapabilityInvocationStatus status = isRejected(category)
                ? CapabilityInvocationStatus.REJECTED
                : CapabilityInvocationStatus.TECHNICAL_FAILED;
        return new CapabilityInvocationResponse(
                CapabilityInvocationRequest.CONTRACT_VERSION,
                request.invocationId(),
                request.qualifiedName(),
                asset == null ? null : asset.name(),
                asset == null ? null : asset.title(),
                status,
                false,
                null,
                code,
                safeFailureMessage(message),
                category,
                retryable,
                elapsedSince(startedAt),
                1,
                null,
                safeMetadata);
    }

    private static boolean isRejected(CapabilityInvocationFailureCategory category) {
        return category == CapabilityInvocationFailureCategory.NOT_FOUND
                || category == CapabilityInvocationFailureCategory.DISABLED
                || category == CapabilityInvocationFailureCategory.POLICY_REJECTED
                || category == CapabilityInvocationFailureCategory.IDENTITY_REQUIRED
                || category == CapabilityInvocationFailureCategory.CONFIGURATION_INVALID;
    }

    private static CapabilityInvocationFailureCategory rejectedCategory(String message) {
        String normalized = failureText(message);
        if (normalized.contains("disabled")) return CapabilityInvocationFailureCategory.DISABLED;
        if (normalized.contains("identity")) return CapabilityInvocationFailureCategory.IDENTITY_REQUIRED;
        if (normalized.contains("policy") || normalized.contains("side_effect")
                || normalized.contains("side-effect") || normalized.contains("does not belong")
                || normalized.contains("does not match")) {
            return CapabilityInvocationFailureCategory.POLICY_REJECTED;
        }
        return CapabilityInvocationFailureCategory.CONFIGURATION_INVALID;
    }

    private static CapabilityInvocationFailureCategory technicalCategory(Throwable failure) {
        StringBuilder chain = new StringBuilder();
        Throwable current = failure;
        for (int depth = 0; current != null && depth < 6; depth++, current = current.getCause()) {
            chain.append(' ').append(current.getClass().getName())
                    .append(' ').append(current.getMessage());
        }
        String normalized = failureText(chain.toString());
        if (normalized.contains("timeout") || normalized.contains("timed out")) {
            return CapabilityInvocationFailureCategory.TIMEOUT;
        }
        if (normalized.contains("connect") || normalized.contains("socket")
                || normalized.contains("unreachable") || normalized.contains("connection refused")) {
            return CapabilityInvocationFailureCategory.CONNECTIVITY;
        }
        return CapabilityInvocationFailureCategory.INTERNAL;
    }

    private static String failureText(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private static String safeFailureMessage(String value) {
        if (!StringUtils.hasText(value)) return "Capability invocation failed";
        String safe = value.replace('\r', ' ').replace('\n', ' ').trim();
        return safe.length() <= 500 ? safe : safe.substring(0, 500);
    }

    private static long elapsedSince(long startedAt) {
        return Math.max(0L, System.currentTimeMillis() - startedAt);
    }

    private static String text(Object value, String fallback) {
        if (value == null) return fallback;
        String result = String.valueOf(value).trim();
        return result.isEmpty() ? fallback : result;
    }
}
