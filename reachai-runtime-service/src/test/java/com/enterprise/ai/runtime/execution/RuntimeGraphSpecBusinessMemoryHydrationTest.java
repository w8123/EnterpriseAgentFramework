package com.enterprise.ai.runtime.execution;

import com.enterprise.ai.common.capability.CapabilityInvocationFailureCategory;
import com.enterprise.ai.common.capability.CapabilityInvocationRequest;
import com.enterprise.ai.common.capability.CapabilityInvocationResponse;
import com.enterprise.ai.common.capability.CapabilityInvocationStatus;
import com.enterprise.ai.runtime.client.capability.RuntimeCapabilityCatalogClient;
import com.enterprise.ai.runtime.client.control.RuntimeControlCatalogClient;
import com.enterprise.ai.runtime.client.model.RuntimeModelServiceClient;
import com.enterprise.ai.runtime.contract.memory.BusinessMemoryResolution;
import com.enterprise.ai.runtime.execution.identity.WorkflowExecutionIdentity;
import com.enterprise.ai.runtime.memory.RuntimeBusinessMemoryHydrationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

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
import static org.mockito.Mockito.when;

class RuntimeGraphSpecBusinessMemoryHydrationTest {

    @Test
    void hydratesBusinessIndexToolOutputBeforeWorkflowCanObserveIt() {
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        RuntimeCapabilityCatalogClient capabilityClient = mock(RuntimeCapabilityCatalogClient.class);
        when(capabilityClient.invokeTool(eq("knowledge:business.search"), any()))
                .thenReturn(success("knowledge:business.search", searchResponse()));
        when(capabilityClient.executeTool(eq("mall:order.resolve"), any()))
                .thenReturn(Map.of(
                        "success", true,
                        "qualifiedName", "mall:order.resolve",
                        "data", resolution()));
        RuntimeBusinessMemoryHydrationService hydration = new RuntimeBusinessMemoryHydrationService(
                capabilityClient, objectMapper, new SimpleMeterRegistry(), 8, 262_144);
        RuntimeGraphSpecExecutor executor = new RuntimeGraphSpecExecutor(
                objectMapper,
                mock(RuntimeModelServiceClient.class),
                capabilityClient,
                mock(RuntimeControlCatalogClient.class),
                null,
                null,
                hydration);

        RuntimeGraphSpecExecutionResult result = executor.execute("""
                {
                  "schemaVersion":2,
                  "entryNodeId":"search",
                  "exitNodeIds":["search"],
                  "nodes":[
                    {"id":"search","type":"TOOL","ref":{"qualifiedName":"knowledge:business.search"}}
                  ],
                  "edges":[]
                }
                """, Map.of(
                "agentId", "agent-1",
                "sessionId", "session-1",
                "supervisorTraceId", "trace-1"),
                WorkflowExecutionIdentity.fromAgent("tenant-a", 9L, "mall", "user-1"));

        assertTrue(result.success());
        assertFalse(result.answer().contains("stale-index-content"));
        assertTrue(result.answer().contains("CURRENT_PAID"));
        assertTrue(result.answer().contains("\"authoritative\":true"));
        @SuppressWarnings("unchecked")
        Map<String, Object> hydrationMeta = (Map<String, Object>) result.metadata()
                .get("businessMemoryHydration");
        assertEquals(1, hydrationMeta.get("resolvedCount"));
    }

    @Test
    void downstreamAnswerObservesOnlyHydratedCurrentData() {
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        RuntimeCapabilityCatalogClient capabilityClient = mock(RuntimeCapabilityCatalogClient.class);
        when(capabilityClient.invokeTool(eq("knowledge:business.search"), any()))
                .thenReturn(success("knowledge:business.search", searchResponse()));
        when(capabilityClient.executeTool(eq("mall:order.resolve"), any()))
                .thenReturn(Map.of(
                        "success", true,
                        "qualifiedName", "mall:order.resolve",
                        "data", resolution()));
        RuntimeBusinessMemoryHydrationService hydration = new RuntimeBusinessMemoryHydrationService(
                capabilityClient, objectMapper, new SimpleMeterRegistry(), 8, 262_144);
        RuntimeGraphSpecExecutor executor = new RuntimeGraphSpecExecutor(
                objectMapper,
                mock(RuntimeModelServiceClient.class),
                capabilityClient,
                mock(RuntimeControlCatalogClient.class),
                null,
                null,
                hydration);

        RuntimeGraphSpecExecutionResult result = executor.execute("""
                {
                  "schemaVersion":2,
                  "entryNodeId":"search",
                  "exitNodeIds":["answer"],
                  "nodes":[
                    {"id":"search","type":"TOOL","ref":{"qualifiedName":"knowledge:business.search"}},
                    {"id":"answer","type":"ANSWER","config":{"template":"{{ lastOutput }}"}}
                  ],
                  "edges":[{"from":"search","to":"answer"}]
                }
                """, Map.of(
                "agentId", "agent-1",
                "sessionId", "session-1",
                "supervisorTraceId", "trace-1"),
                WorkflowExecutionIdentity.fromAgent("tenant-a", 9L, "mall", "user-1"));

        assertTrue(result.success());
        assertFalse(result.answer().contains("stale-index-content"));
        assertTrue(result.answer().contains("CURRENT_PAID"));
        assertTrue(result.answer().contains("authoritative=true"));
    }

    private Map<String, Object> searchResponse() {
        Map<String, Object> reference = new LinkedHashMap<>();
        reference.put("schema", "reachai-business-memory-reference-v1");
        reference.put("tenantId", "tenant-a");
        reference.put("projectCode", "mall");
        reference.put("sourceSystem", "mall-order");
        reference.put("resourceType", "order");
        reference.put("resourceId", "O-1");
        reference.put("sourceVersion", "v12");
        reference.put("resolverCapabilityKey", "mall:order.resolve");
        reference.put("observedAt", OffsetDateTime.now(ZoneOffset.UTC).toString());
        Map<String, Object> hit = new LinkedHashMap<>();
        hit.put("bizId", "O-1");
        hit.put("score", 0.95d);
        hit.put("matchSource", "FIELD");
        hit.put("matchContent", "stale-index-content");
        hit.put("businessMemoryReference", reference);
        hit.put("authoritative", false);
        hit.put("hydrationRequired", true);
        hit.put("agentMemoryEligible", true);
        return Map.of("results", List.of(hit), "total", 1);
    }

    private Map<String, Object> resolution() {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("schema", BusinessMemoryResolution.SCHEMA);
        value.put("tenantId", "tenant-a");
        value.put("projectCode", "mall");
        value.put("sourceSystem", "mall-order");
        value.put("resourceType", "order");
        value.put("resourceId", "O-1");
        value.put("sourceVersion", "v12");
        value.put("data", Map.of("status", "CURRENT_PAID"));
        value.put("resolvedAt", OffsetDateTime.now(ZoneOffset.UTC).toString());
        return value;
    }

    private CapabilityInvocationResponse success(String qualifiedName, Object data) {
        return new CapabilityInvocationResponse(
                CapabilityInvocationRequest.CONTRACT_VERSION,
                "test-invocation",
                qualifiedName,
                null,
                null,
                CapabilityInvocationStatus.SUCCEEDED,
                true,
                data,
                "OK",
                null,
                CapabilityInvocationFailureCategory.NONE,
                false,
                1L,
                1,
                null,
                Map.of());
    }
}
