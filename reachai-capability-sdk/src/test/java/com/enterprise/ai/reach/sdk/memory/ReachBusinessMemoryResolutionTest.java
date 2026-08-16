package com.enterprise.ai.reach.sdk.memory;

import com.enterprise.ai.reach.sdk.annotation.ReachCapability;
import com.enterprise.ai.reach.sdk.annotation.ReachParam;
import com.enterprise.ai.reach.sdk.annotation.ReachSideEffectLevel;
import com.enterprise.ai.reach.sdk.capability.ReachCapabilityDescriptor;
import com.enterprise.ai.reach.sdk.capability.ReachCapabilityParameter;
import com.enterprise.ai.reach.sdk.capability.ReachCapabilityScanner;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReachBusinessMemoryResolutionTest {

    @Test
    void serializesTheRuntimeResolutionContractWithoutJavaTimeModule() throws Exception {
        ReachBusinessMemoryAccessScope scope = ReachBusinessMemoryAccessScope.authorizeRow(
                "tenant-a", "tenant-a", "user-1", "mall", "order", "O-1", "O-1", true);
        ReachBusinessMemoryResolution result = ReachBusinessMemoryResolution.createAuthorized(
                scope, "mall-order", "v13",
                Collections.<String, Object>singletonMap("status", "PAID"));

        String json = new ObjectMapper().writeValueAsString(result);

        assertTrue(json.contains("\"schema\":\"reachai-business-memory-resolution-v1\""));
        assertTrue(json.contains("\"sourceVersion\":\"v13\""));
        assertTrue(json.contains("\"resolvedAt\":"));
        assertFalse(json.contains("rowAuthorizationVerified"));
        assertTrue(result.isRowAuthorizationVerified());
        assertEquals("PAID", result.getData().get("status"));
    }

    @Test
    void rejectsCrossTenantOrInvisibleSourceRows() {
        assertThrows(SecurityException.class, () -> ReachBusinessMemoryAccessScope.authorizeRow(
                "tenant-a", "tenant-b", "user-1", "mall", "order", "O-1", "O-1", true));
        assertThrows(SecurityException.class, () -> ReachBusinessMemoryAccessScope.authorizeRow(
                "tenant-a", "tenant-a", "user-1", "mall", "order", "O-1", "O-1", false));
        assertThrows(SecurityException.class, () -> ReachBusinessMemoryAccessScope.authorizeRow(
                "tenant-a", "tenant-a", "user-1", "mall", "order", "O-1", "O-2", true));
    }

    @Test
    void compatibilityFactoryDoesNotClaimRowAuthorization() {
        ReachBusinessMemoryResolution result = ReachBusinessMemoryResolution.create(
                "tenant-a", "mall", "mall-order", "order", "O-1", "v13",
                Collections.<String, Object>emptyMap());

        assertFalse(result.isRowAuthorizationVerified());
    }

    @Test
    void rejectsInvalidScopeIdentifiers() {
        assertThrows(IllegalArgumentException.class, () -> ReachBusinessMemoryResolution.create(
                "tenant with spaces", "mall", "mall-order", "order", "O-1", "v1",
                Collections.<String, Object>emptyMap()));
    }

    @Test
    void publishesResolverFieldSemanticsThroughTheCapabilityScanner() {
        ReachCapabilityDescriptor descriptor = ReachCapabilityScanner
                .scanClasses(ResolverExample.class)
                .get(0);

        List<ReachCapabilityParameter> parameters = descriptor.getParameters();
        assertTrue(parameters.stream().anyMatch(parameter ->
                "request.resourceType".equals(parameter.getName()) && parameter.isRequired()));
        assertTrue(parameters.stream().anyMatch(parameter ->
                "request.resourceId".equals(parameter.getName())
                        && parameter.isRequired()
                        && parameter.isSensitive()));
        assertTrue(parameters.stream().anyMatch(parameter ->
                "request.expectedSourceVersion".equals(parameter.getName()) && parameter.isRequired()));
    }

    private static final class ResolverExample {

        @ReachCapability(
                name = "order.memory.resolve",
                sideEffect = ReachSideEffectLevel.READ)
        public ReachBusinessMemoryResolution resolve(
                @ReachParam(name = "request", required = true)
                ReachBusinessMemoryResolverRequest request) {
            return null;
        }
    }
}
