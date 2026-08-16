package com.enterprise.ai.reach.spring;

import com.enterprise.ai.reach.sdk.annotation.ReachCapability;
import com.enterprise.ai.reach.sdk.annotation.ReachParam;
import com.enterprise.ai.reach.sdk.annotation.ReachSideEffectLevel;
import com.enterprise.ai.reach.sdk.auth.ReachAiInvocationClaims;
import com.enterprise.ai.reach.sdk.auth.ReachAiInvocationToken;
import com.enterprise.ai.reach.sdk.memory.ReachBusinessMemoryAccessScope;
import com.enterprise.ai.reach.sdk.memory.ReachBusinessMemoryResolution;
import com.enterprise.ai.reach.sdk.memory.ReachBusinessMemoryResolverRequest;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ReachBusinessMemoryResolverGuardTest {

    private static final String PROJECT = "mall";
    private static final String APP_KEY = "demo-key";
    private static final String SECRET = "secret";

    @Test
    void acceptsAuthorizedResolutionWhoseScopeMatchesSignedRequest() {
        MemoryResolvers resolvers = new MemoryResolvers();
        ReachCapabilityEndpoint endpoint = endpoint(resolvers);

        ReachBusinessMemoryResolution result = (ReachBusinessMemoryResolution) endpoint.invoke(
                "order.memory.resolve", token("order.memory.resolve", "tenant-a", "user-1"), request());

        assertEquals("tenant-a", result.getTenantId());
        assertEquals("O-1", result.getResourceId());
        assertEquals("PAID", result.getData().get("status"));
    }

    @Test
    void rejectsLegacyResolutionWithoutRowAuthorizationProof() {
        ReachCapabilityEndpoint endpoint = endpoint(new MemoryResolvers());

        assertThrows(SecurityException.class, () -> endpoint.invoke(
                "order.memory.legacy", token("order.memory.legacy", "tenant-a", "user-1"), request()));
    }

    @Test
    void typedResolverCannotBypassTheGuardByOmittingTheTag() {
        ReachCapabilityEndpoint endpoint = endpoint(new MemoryResolvers());

        assertThrows(SecurityException.class, () -> endpoint.invoke(
                "order.memory.untagged",
                token("order.memory.untagged", "tenant-a", "user-1"), request()));
    }

    @Test
    void rejectsCrossTenantResolutionEvenWhenTheBusinessMethodClaimsItWasAuthorized() {
        ReachCapabilityEndpoint endpoint = endpoint(new MemoryResolvers());

        assertThrows(SecurityException.class, () -> endpoint.invoke(
                "order.memory.cross-tenant",
                token("order.memory.cross-tenant", "tenant-a", "user-1"), request()));
    }

    @Test
    void rejectsMissingSignedUserBeforeCallingBusinessCode() {
        MemoryResolvers resolvers = new MemoryResolvers();
        ReachCapabilityEndpoint endpoint = endpoint(resolvers);

        assertThrows(IllegalArgumentException.class, () -> endpoint.invoke(
                "order.memory.resolve", token("order.memory.resolve", "tenant-a", null), request()));

        assertFalse(resolvers.called);
    }

    @Test
    void rejectsIncompleteRequestBeforeCallingBusinessCode() {
        MemoryResolvers resolvers = new MemoryResolvers();
        ReachCapabilityEndpoint endpoint = endpoint(resolvers);
        Map<String, Object> request = request();
        request.remove("expectedSourceVersion");

        assertThrows(IllegalArgumentException.class, () -> endpoint.invoke(
                "order.memory.resolve", token("order.memory.resolve", "tenant-a", "user-1"), request));

        assertFalse(resolvers.called);
    }

    private ReachCapabilityEndpoint endpoint(Object bean) {
        ReachAiRegistryProperties properties = new ReachAiRegistryProperties();
        properties.getProject().setCode(PROJECT);
        properties.getRegistry().setAppKey(APP_KEY);
        properties.getRegistry().setAppSecret(SECRET);
        properties.getCapability().setRequireInvocationToken(true);
        return new ReachCapabilityEndpoint(
                new ReachCapabilityInvoker(new Object[]{bean}),
                new ReachCapabilityInvocationVerifier(properties),
                Collections.<ReachAiSecurityContextBridge>emptyList());
    }

    private String token(String capabilityName, String tenantId, String userId) {
        ReachAiInvocationClaims claims = ReachAiInvocationClaims.builder()
                .projectCode(PROJECT)
                .appKey(APP_KEY)
                .capabilityName(capabilityName)
                .tenantId(tenantId)
                .externalUserId(userId)
                .agentId("agent-1")
                .sessionId("session-1")
                .traceId("trace-1")
                .build();
        return ReachAiInvocationToken.sign(SECRET, claims, System.currentTimeMillis(), 120);
    }

    private Map<String, Object> request() {
        Map<String, Object> request = new LinkedHashMap<String, Object>();
        request.put("resourceType", "order");
        request.put("resourceId", "O-1");
        request.put("expectedSourceVersion", "v1");
        return request;
    }

    static class MemoryResolvers {
        private boolean called;

        @ReachCapability(
                name = "order.memory.resolve",
                tags = {ReachBusinessMemoryResolverGuard.TAG},
                sideEffect = ReachSideEffectLevel.READ)
        public ReachBusinessMemoryResolution resolve(
                @ReachParam(name = "request", required = true)
                ReachBusinessMemoryResolverRequest request) {
            called = true;
            ReachAiInvocationContext context = ReachAiInvocationContextHolder.getRequired();
            ReachBusinessMemoryAccessScope scope = ReachBusinessMemoryAccessScope.authorizeRow(
                    context.getTenantId(), "tenant-a", context.getExternalUserId(),
                    context.getProjectCode(), request.getResourceType(),
                    request.getResourceId(), request.getResourceId(), true);
            return ReachBusinessMemoryResolution.createAuthorized(
                    scope, "mall-order", "v2",
                    Collections.<String, Object>singletonMap("status", "PAID"));
        }

        @ReachCapability(
                name = "order.memory.legacy",
                tags = {ReachBusinessMemoryResolverGuard.TAG},
                sideEffect = ReachSideEffectLevel.READ)
        public ReachBusinessMemoryResolution legacy(
                @ReachParam(name = "request", required = true)
                ReachBusinessMemoryResolverRequest request) {
            return ReachBusinessMemoryResolution.create(
                    "tenant-a", PROJECT, "mall-order", request.getResourceType(),
                    request.getResourceId(), "v2", Collections.<String, Object>emptyMap());
        }

        @ReachCapability(
                name = "order.memory.untagged",
                sideEffect = ReachSideEffectLevel.READ)
        public ReachBusinessMemoryResolution untagged(
                @ReachParam(name = "request", required = true)
                ReachBusinessMemoryResolverRequest request) {
            return ReachBusinessMemoryResolution.create(
                    "tenant-a", PROJECT, "mall-order", request.getResourceType(),
                    request.getResourceId(), "v2", Collections.<String, Object>emptyMap());
        }

        @ReachCapability(
                name = "order.memory.cross-tenant",
                tags = {ReachBusinessMemoryResolverGuard.TAG},
                sideEffect = ReachSideEffectLevel.READ)
        public ReachBusinessMemoryResolution crossTenant(
                @ReachParam(name = "request", required = true)
                ReachBusinessMemoryResolverRequest request) {
            ReachBusinessMemoryAccessScope scope = ReachBusinessMemoryAccessScope.authorizeRow(
                    "tenant-b", "tenant-b", "user-1", PROJECT, request.getResourceType(),
                    request.getResourceId(), request.getResourceId(), true);
            return ReachBusinessMemoryResolution.createAuthorized(
                    scope, "mall-order", "v2", Collections.<String, Object>emptyMap());
        }
    }
}
