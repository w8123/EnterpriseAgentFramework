package com.enterprise.ai.capability.internal;

import com.enterprise.ai.agent.registry.ProjectInstanceEntity;
import com.enterprise.ai.agent.registry.ProjectInstanceMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CapabilityRuntimeInstanceLookupServiceTest {

    @Test
    void listsRuntimeInstancesForRuntimeServiceWithoutExposingPersistenceTypes() {
        ProjectInstanceMapper mapper = mock(ProjectInstanceMapper.class);
        CapabilityRuntimeInstanceLookupService service =
                new CapabilityRuntimeInstanceLookupService(mapper, new ObjectMapper());
        ProjectInstanceEntity entity = new ProjectInstanceEntity();
        entity.setProjectCode("orders");
        entity.setInstanceId("host-1");
        entity.setBaseUrl("http://orders");
        entity.setHost("orders-host");
        entity.setPort(8080);
        entity.setAppVersion("1.0.0");
        entity.setSdkVersion("0.3.0");
        entity.setStatus("ONLINE");
        entity.setMetadataJson("{\"runtimePlacement\":\"CAPABILITY_HOST\",\"runtimeTypes\":[\"SPRING_BOOT2_CAPABILITY_HOST\"],\"supportsTools\":true}");
        entity.setGovernancePolicyJson("{\"disabled\":false,\"allowEmbeddedExecution\":true,\"allowHybridExecution\":true,\"message\":\"ok\"}");
        entity.setLastHeartbeatAt(LocalDateTime.parse("2026-06-29T10:00:00"));
        when(mapper.selectList(any())).thenReturn(List.of(entity));

        List<Map<String, Object>> instances = service.listRuntimeInstances();

        assertEquals(1, instances.size());
        Map<String, Object> instance = instances.get(0);
        assertEquals("orders", instance.get("projectCode"));
        assertEquals("host-1", instance.get("instanceId"));
        assertEquals("ONLINE", instance.get("status"));
        assertEquals("http://orders", instance.get("baseUrl"));
        assertEquals("SPRING_BOOT2_CAPABILITY_HOST", ((List<?>) instance.get("runtimeTypes")).get(0));
        assertEquals(true, instance.get("supportsTools"));
        @SuppressWarnings("unchecked")
        Map<String, Object> policy = (Map<String, Object>) instance.get("governancePolicy");
        assertEquals(false, policy.get("disabled"));
        assertEquals(true, policy.get("allowEmbeddedExecution"));
    }
}
