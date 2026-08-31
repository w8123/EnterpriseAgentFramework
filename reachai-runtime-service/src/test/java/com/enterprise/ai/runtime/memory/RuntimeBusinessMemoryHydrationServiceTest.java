package com.enterprise.ai.runtime.memory;

import com.enterprise.ai.runtime.execution.RuntimeBusinessMemoryHydrationPort;
import com.enterprise.ai.runtime.client.capability.RuntimeCapabilityCatalogClient;
import com.enterprise.ai.runtime.contract.memory.BusinessMemoryResolution;
import com.enterprise.ai.runtime.execution.identity.WorkflowExecutionIdentity;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RuntimeBusinessMemoryHydrationServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

    @Test
    void replacesProjectionTextWithCurrentResolverAuthorizedData() throws Exception {
        RuntimeCapabilityCatalogClient client = mock(RuntimeCapabilityCatalogClient.class);
        when(client.executeTool(eq("mall:order.resolve"), any())).thenReturn(resolverResponse("v12", "PAID"));
        RuntimeBusinessMemoryHydrationService service = service(client);

        RuntimeBusinessMemoryHydrationPort.HydrationBatch batch = service.hydrate(
                Map.of("results", List.of(indexHit(true, "tenant-a", "mall", "v12"))),
                WorkflowExecutionIdentity.fromAgent("tenant-a", 9L, "mall", "user-1"),
                Map.of("agentId", "agent-1", "sessionId", "session-1", "supervisorTraceId", "trace-1"));

        assertTrue(batch.detected());
        assertEquals(1, batch.resolvedCount());
        assertEquals(0, batch.blockedCount());
        String serialized = objectMapper.writeValueAsString(batch.output());
        assertFalse(serialized.contains("stale-index-content"));
        assertFalse(serialized.contains("staleMetadata"));
        assertTrue(serialized.contains("PAID"));
        assertTrue(serialized.contains("\"authoritative\":true"));
        assertTrue(serialized.contains("\"hydrationRequired\":false"));

        ArgumentCaptor<Map<String, Object>> request = ArgumentCaptor.forClass(Map.class);
        verify(client).executeTool(eq("mall:order.resolve"), request.capture());
        assertEquals(Map.of(
                "resourceType", "order",
                "resourceId", "O-1",
                "expectedSourceVersion", "v12"), request.getValue().get("input"));
        @SuppressWarnings("unchecked")
        Map<String, Object> context = (Map<String, Object>) request.getValue().get("context");
        assertEquals("tenant-a", context.get("tenantId"));
        assertEquals("user-1", context.get("externalUserId"));
        assertFalse(context.containsKey("roles"));
        @SuppressWarnings("unchecked")
        Map<String, Object> constraints = (Map<String, Object>) request.getValue().get("constraints");
        assertEquals("mall", constraints.get("expectedProjectCode"));
        assertEquals(true, constraints.get("requireSignedInvocation"));
    }

    @Test
    void acceptsCurrentResolverVersionButMarksStaleIndexVersion() throws Exception {
        RuntimeCapabilityCatalogClient client = mock(RuntimeCapabilityCatalogClient.class);
        when(client.executeTool(eq("mall:order.resolve"), any())).thenReturn(resolverResponse("v13", "SHIPPED"));
        RuntimeBusinessMemoryHydrationPort.HydrationBatch batch = service(client).hydrate(
                indexHit(true, "tenant-a", "mall", "v12"),
                WorkflowExecutionIdentity.fromAgent("tenant-a", 9L, "mall", "user-1"), Map.of());

        assertEquals(1, batch.resolvedCount());
        assertEquals(1, batch.versionChangedCount());
        String serialized = objectMapper.writeValueAsString(batch.output());
        assertTrue(serialized.contains("RESOLVED_VERSION_CHANGED"));
        assertTrue(serialized.contains("\"indexVersionMatched\":false"));
        assertTrue(serialized.contains("SHIPPED"));
    }

    @Test
    void stripsLegacyAndScopeMismatchedHitsWithoutCallingResolver() throws Exception {
        RuntimeCapabilityCatalogClient client = mock(RuntimeCapabilityCatalogClient.class);
        RuntimeBusinessMemoryHydrationService service = service(client);
        List<Map<String, Object>> hits = List.of(
                indexHit(false, "tenant-a", "mall", "v1"),
                indexHit(true, "tenant-b", "mall", "v1"));

        RuntimeBusinessMemoryHydrationPort.HydrationBatch batch = service.hydrate(
                Map.of("results", hits),
                WorkflowExecutionIdentity.fromAgent("tenant-a", 9L, "mall", "user-1"), Map.of());

        assertEquals(2, batch.referenceCount());
        assertEquals(0, batch.resolvedCount());
        assertEquals(2, batch.blockedCount());
        String serialized = objectMapper.writeValueAsString(batch.output());
        assertFalse(serialized.contains("stale-index-content"));
        assertFalse(serialized.contains("O-1"));
        assertTrue(serialized.contains("NOT_AGENT_ELIGIBLE"));
        assertTrue(serialized.contains("SCOPE_DENIED"));
        verify(client, never()).executeTool(any(), any());
    }

    @Test
    void stripsProjectionWhenResolverReturnsDifferentResource() throws Exception {
        RuntimeCapabilityCatalogClient client = mock(RuntimeCapabilityCatalogClient.class);
        Map<String, Object> mismatched = resolverResponse("v12", "PAID");
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) mismatched.get("data");
        payload.put("resourceId", "O-2");
        when(client.executeTool(eq("mall:order.resolve"), any())).thenReturn(mismatched);

        RuntimeBusinessMemoryHydrationPort.HydrationBatch batch = service(client).hydrate(
                indexHit(true, "tenant-a", "mall", "v12"),
                WorkflowExecutionIdentity.fromAgent("tenant-a", 9L, "mall", "user-1"), Map.of());

        assertEquals(0, batch.resolvedCount());
        assertEquals(1, batch.blockedCount());
        String serialized = objectMapper.writeValueAsString(batch.output());
        assertFalse(serialized.contains("PAID"));
        assertFalse(serialized.contains("stale-index-content"));
        assertFalse(serialized.contains("O-1"));
        assertFalse(serialized.contains("mall:order.resolve"));
        assertTrue(serialized.contains("RESOLVER_REJECTED"));
    }

    @Test
    void stripsIdentifiersAndProjectionWhenAuthorizedResponseExceedsTheBound() throws Exception {
        RuntimeCapabilityCatalogClient client = mock(RuntimeCapabilityCatalogClient.class);
        Map<String, Object> oversized = resolverResponse("v12", "x".repeat(5_000));
        when(client.executeTool(eq("mall:order.resolve"), any())).thenReturn(oversized);
        RuntimeBusinessMemoryHydrationService service = new RuntimeBusinessMemoryHydrationService(
                client, objectMapper, meterRegistry, 8, 4_096);

        RuntimeBusinessMemoryHydrationPort.HydrationBatch batch = service.hydrate(
                indexHit(true, "tenant-a", "mall", "v12"),
                WorkflowExecutionIdentity.fromAgent("tenant-a", 9L, "mall", "user-1"), Map.of());

        assertEquals(0, batch.resolvedCount());
        assertEquals(1, batch.blockedCount());
        String serialized = objectMapper.writeValueAsString(batch.output());
        assertTrue(serialized.contains("RESPONSE_TOO_LARGE"));
        assertFalse(serialized.contains("O-1"));
        assertFalse(serialized.contains("stale-index-content"));
        assertFalse(serialized.contains("mall:order.resolve"));
    }

    private RuntimeBusinessMemoryHydrationService service(RuntimeCapabilityCatalogClient client) {
        return new RuntimeBusinessMemoryHydrationService(client, objectMapper, meterRegistry, 8, 262_144);
    }

    private Map<String, Object> indexHit(boolean eligible,
                                         String tenantId,
                                         String projectCode,
                                         String sourceVersion) {
        Map<String, Object> reference = new LinkedHashMap<>();
        reference.put("schema", "reachai-business-memory-reference-v1");
        reference.put("tenantId", tenantId);
        reference.put("projectCode", projectCode);
        reference.put("sourceSystem", "mall-order");
        reference.put("resourceType", "order");
        reference.put("resourceId", "O-1");
        reference.put("sourceVersion", sourceVersion);
        reference.put("resolverCapabilityKey", "mall:order.resolve");
        reference.put("observedAt", OffsetDateTime.now(ZoneOffset.UTC).toString());
        Map<String, Object> hit = new LinkedHashMap<>();
        hit.put("bizId", "O-1");
        hit.put("bizType", "order");
        hit.put("score", 0.93d);
        hit.put("matchSource", "FIELD");
        hit.put("matchContent", "stale-index-content");
        hit.put("metadata", Map.of("staleMetadata", true));
        hit.put("businessMemoryReference", eligible ? reference : null);
        hit.put("authoritative", false);
        hit.put("hydrationRequired", eligible);
        hit.put("agentMemoryEligible", eligible);
        return hit;
    }

    private Map<String, Object> resolverResponse(String sourceVersion, String status) {
        Map<String, Object> resolution = new LinkedHashMap<>();
        resolution.put("schema", BusinessMemoryResolution.SCHEMA);
        resolution.put("tenantId", "tenant-a");
        resolution.put("projectCode", "mall");
        resolution.put("sourceSystem", "mall-order");
        resolution.put("resourceType", "order");
        resolution.put("resourceId", "O-1");
        resolution.put("sourceVersion", sourceVersion);
        resolution.put("data", Map.of("status", status));
        resolution.put("resolvedAt", OffsetDateTime.now(ZoneOffset.UTC).toString());
        return new LinkedHashMap<>(Map.of(
                "success", true,
                "qualifiedName", "mall:order.resolve",
                "data", resolution));
    }
}
