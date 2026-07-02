package com.enterprise.ai.runtime.registry;

import com.enterprise.ai.runtime.client.capability.RuntimeCapabilityCatalogClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RuntimeRegistryServiceTest {

    @Test
    void listsPlatformRuntimeAndCapabilityHostInstances() {
        RuntimeCapabilityCatalogClient capabilityClient = mock(RuntimeCapabilityCatalogClient.class);
        Map<String, Object> instance = new LinkedHashMap<>();
        instance.put("projectCode", "orders");
        instance.put("instanceId", "host-1");
        instance.put("baseUrl", "http://orders");
        instance.put("host", "orders-host");
        instance.put("port", 8080);
        instance.put("appVersion", "1.0.0");
        instance.put("sdkVersion", "0.3.0");
        instance.put("status", "ONLINE");
        instance.put("lastHeartbeatAt", LocalDateTime.parse("2026-06-29T10:00:00"));
        instance.put("runtimePlacement", "CAPABILITY_HOST");
        instance.put("runtimeTypes", List.of("SPRING_BOOT2_CAPABILITY_HOST"));
        instance.put("supportsTools", true);
        instance.put("supportsGraph", false);
        instance.put("supportsHybridExecution", true);
        instance.put("governancePolicy", Map.of(
                "disabled", false,
                "allowEmbeddedExecution", true,
                "allowHybridExecution", true,
                "message", "ok"
        ));
        when(capabilityClient.listRuntimeInstances()).thenReturn(List.of(instance));
        RuntimeRegistryService service = new RuntimeRegistryService(capabilityClient, new ObjectMapper());

        List<RuntimeRegistryEntry> entries = service.listRuntimes();

        assertTrue(entries.stream().anyMatch(entry -> "platform:LANGGRAPH4J".equals(entry.id())));
        RuntimeRegistryEntry capabilityHost = entries.stream()
                .filter(entry -> "instance:orders:host-1".equals(entry.id()))
                .findFirst()
                .orElseThrow();
        assertEquals("PROJECT_INSTANCE", capabilityHost.source());
        assertEquals("CAPABILITY_HOST", capabilityHost.runtimeRole());
        assertEquals("CAPABILITY_HOST", capabilityHost.runtimePlacement());
        assertEquals("orders", capabilityHost.projectCode());
        assertEquals("host-1", capabilityHost.instanceId());
        assertEquals(true, capabilityHost.supportsTools());
    }
}
