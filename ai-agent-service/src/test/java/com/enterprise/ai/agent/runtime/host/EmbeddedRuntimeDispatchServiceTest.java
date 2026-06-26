package com.enterprise.ai.agent.runtime.host;


import com.enterprise.ai.agent.runtime.EmbeddedRuntimeDispatchRequest;
import com.enterprise.ai.agent.registry.AiRegistryService;
import com.enterprise.ai.agent.registry.ProjectInstanceEntity;
import com.enterprise.ai.agent.registry.RegistryContracts.RuntimeGovernancePolicy;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class EmbeddedRuntimeDispatchServiceTest {

    @Test
    void rejectsOfflineInstanceBeforeRemoteDispatch() {
        AiRegistryService registryService = mock(AiRegistryService.class);
        ProjectInstanceEntity instance = instance("OFFLINE");
        when(registryService.findInstance("crm", "i-1")).thenReturn(instance);
        when(registryService.governancePolicy(instance)).thenReturn(policy(false, true));
        EmbeddedRuntimeDispatchService service = new EmbeddedRuntimeDispatchService(registryService);

        IllegalStateException ex = assertThrows(
                IllegalStateException.class,
                () -> service.dispatch(request()));

        assertEquals("Runtime 实例不在线: OFFLINE", ex.getMessage());
    }

    @Test
    void rejectsDisabledGovernancePolicyBeforeRemoteDispatch() {
        AiRegistryService registryService = mock(AiRegistryService.class);
        ProjectInstanceEntity instance = instance("ONLINE");
        when(registryService.findInstance("crm", "i-1")).thenReturn(instance);
        when(registryService.governancePolicy(instance)).thenReturn(policy(true, true));
        EmbeddedRuntimeDispatchService service = new EmbeddedRuntimeDispatchService(registryService);

        IllegalStateException ex = assertThrows(
                IllegalStateException.class,
                () -> service.dispatch(request()));

        assertEquals("Runtime 实例已被治理策略禁用: blocked", ex.getMessage());
    }

    @Test
    void rejectsEmbeddedDispatchWhenPolicyDisallowsEmbeddedExecution() {
        AiRegistryService registryService = mock(AiRegistryService.class);
        ProjectInstanceEntity instance = instance("ONLINE");
        when(registryService.findInstance("crm", "i-1")).thenReturn(instance);
        when(registryService.governancePolicy(instance)).thenReturn(policy(false, false));
        EmbeddedRuntimeDispatchService service = new EmbeddedRuntimeDispatchService(registryService);

        IllegalStateException ex = assertThrows(
                IllegalStateException.class,
                () -> service.dispatch(request()));

        assertEquals("Runtime 实例未被允许执行 Embedded Runtime: blocked", ex.getMessage());
    }

    @Test
    void rejectsCapabilityHostBeforeRemoteDispatch() {
        AiRegistryService registryService = mock(AiRegistryService.class);
        ProjectInstanceEntity instance = instance("ONLINE");
        instance.setMetadataJson("""
                {"runtimePlacement":"CAPABILITY_HOST","runtimeTypes":["SPRING_BOOT2_CAPABILITY_HOST"]}
                """);
        when(registryService.findInstance("crm", "i-1")).thenReturn(instance);
        when(registryService.governancePolicy(instance)).thenReturn(policy(false, true));
        EmbeddedRuntimeDispatchService service = new EmbeddedRuntimeDispatchService(registryService);

        IllegalStateException ex = assertThrows(
                IllegalStateException.class,
                () -> service.dispatch(request()));

        assertEquals("Capability Host 只能提供业务能力调用，不能作为 Agent Runtime 执行目标", ex.getMessage());
    }

    private EmbeddedRuntimeDispatchRequest request() {
        return new EmbeddedRuntimeDispatchRequest("crm", "i-1", "agent-a", "hello", "s1", "u1", Map.of(), Map.of());
    }

    private ProjectInstanceEntity instance(String status) {
        ProjectInstanceEntity entity = new ProjectInstanceEntity();
        entity.setProjectCode("crm");
        entity.setInstanceId("i-1");
        entity.setStatus(status);
        entity.setBaseUrl("http://localhost:18080");
        return entity;
    }

    private RuntimeGovernancePolicy policy(boolean disabled, boolean allowEmbeddedExecution) {
        return new RuntimeGovernancePolicy(
                disabled,
                disabled ? "DISABLED" : "ONLINE",
                null,
                allowEmbeddedExecution,
                true,
                "blocked"
        );
    }
}
