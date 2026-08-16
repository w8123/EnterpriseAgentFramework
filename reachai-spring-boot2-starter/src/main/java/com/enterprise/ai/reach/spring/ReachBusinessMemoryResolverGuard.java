package com.enterprise.ai.reach.spring;

import com.enterprise.ai.reach.sdk.memory.ReachBusinessMemoryResolution;
import org.springframework.util.StringUtils;

import java.util.Map;

final class ReachBusinessMemoryResolverGuard {

    static final String TAG = "business-memory-resolver";

    private ReachBusinessMemoryResolverGuard() {
    }

    static InvocationScope requireInvocation(ReachAiInvocationContext context,
                                             String capabilityName,
                                             Map<String, Object> arguments) {
        if (context == null) {
            throw new SecurityException("business-memory resolver requires a signed invocation");
        }
        String tenantId = required(context.getTenantId(), "signed tenantId");
        String userId = firstNonBlank(context.getExternalUserId(), context.getGlobalUserId());
        required(userId, "signed userId");
        String projectCode = required(context.getProjectCode(), "signed projectCode");
        if (!required(capabilityName, "capabilityName")
                .equals(required(context.getCapabilityName(), "signed capabilityName"))) {
            throw new SecurityException("business-memory capability scope denied");
        }
        Map<String, Object> request = request(arguments);
        String resourceType = requiredText(request.get("resourceType"), "resourceType");
        String resourceId = requiredText(request.get("resourceId"), "resourceId");
        requiredText(request.get("expectedSourceVersion"), "expectedSourceVersion");
        return new InvocationScope(tenantId, projectCode, resourceType, resourceId);
    }

    static Object validateResult(InvocationScope scope, Object result) {
        if (!(result instanceof ReachBusinessMemoryResolution)) {
            throw new SecurityException("business-memory resolver returned an unsupported contract");
        }
        ReachBusinessMemoryResolution resolution = (ReachBusinessMemoryResolution) result;
        if (!resolution.isRowAuthorizationVerified()) {
            throw new SecurityException("business-memory row authorization proof is required");
        }
        requireEqual(scope.tenantId, resolution.getTenantId(), "tenant");
        requireEqual(scope.projectCode, resolution.getProjectCode(), "project");
        requireEqual(scope.resourceType, resolution.getResourceType(), "resource type");
        requireEqual(scope.resourceId, resolution.getResourceId(), "resource id");
        return resolution;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> request(Map<String, Object> arguments) {
        if (arguments == null) {
            throw new IllegalArgumentException("business-memory request is required");
        }
        Object wrapped = arguments.get("request");
        if (wrapped instanceof Map) {
            return (Map<String, Object>) wrapped;
        }
        return arguments;
    }

    private static void requireEqual(String expected, String actual, String field) {
        if (!expected.equals(required(actual, "resolution " + field))) {
            throw new SecurityException("business-memory " + field + " scope denied");
        }
    }

    private static String requiredText(Object value, String field) {
        return required(value == null ? null : String.valueOf(value), field);
    }

    private static String required(String value, String field) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.trim();
    }

    private static String firstNonBlank(String primary, String fallback) {
        return StringUtils.hasText(primary) ? primary.trim()
                : StringUtils.hasText(fallback) ? fallback.trim() : null;
    }

    static final class InvocationScope {
        private final String tenantId;
        private final String projectCode;
        private final String resourceType;
        private final String resourceId;

        private InvocationScope(String tenantId, String projectCode,
                                String resourceType, String resourceId) {
            this.tenantId = tenantId;
            this.projectCode = projectCode;
            this.resourceType = resourceType;
            this.resourceId = resourceId;
        }
    }
}
