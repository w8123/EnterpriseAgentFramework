package com.enterprise.ai.agent.runtime.host;



import com.enterprise.ai.agent.runtime.RuntimeRegistryEntry;
import com.enterprise.ai.agent.runtime.AgentRuntimeCapability;
import com.enterprise.ai.agent.registry.AiRegistryService;
import com.enterprise.ai.agent.registry.ProjectInstanceEntity;
import com.enterprise.ai.agent.registry.RegistryContracts.RuntimeGovernancePolicy;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RuntimeRegistryServiceTest {

    @Test
    void marksSpringBoot2HeartbeatAsCapabilityHost() {
        AgentRuntimeSelector runtimeSelector = mock(AgentRuntimeSelector.class);
        AiRegistryService registryService = mock(AiRegistryService.class);
        ProjectInstanceEntity instance = new ProjectInstanceEntity();
        instance.setProjectCode("contract-system");
        instance.setInstanceId("host-1");
        instance.setHost("biz-host");
        instance.setStatus("ONLINE");
        instance.setMetadataJson("""
                {
                  "runtimePlacement": "CAPABILITY_HOST",
                  "runtimeTypes": ["SPRING_BOOT2_CAPABILITY_HOST"],
                  "supportsTools": true,
                  "supportsGraph": false,
                  "supportsHybridExecution": true,
                  "capabilityCount": 3
                }
                """);

        when(runtimeSelector.capabilities()).thenReturn(List.of());
        when(registryService.listAllInstances()).thenReturn(List.of(instance));
        when(registryService.governancePolicy(instance))
                .thenReturn(new RuntimeGovernancePolicy(false, "ONLINE", null, true, true, "ok"));

        RuntimeRegistryService service = new RuntimeRegistryService(runtimeSelector, registryService, new ObjectMapper());

        RuntimeRegistryEntry entry = service.list().get(0);

        assertEquals("CAPABILITY_HOST", entry.runtimeRole());
        assertEquals("CAPABILITY_HOST", entry.runtimePlacement());
        assertEquals("Capability Host", entry.displayName());
        assertTrue(entry.supportsTools());
        assertEquals(3, entry.metadata().get("capabilityCount"));
    }
}
