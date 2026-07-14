package com.enterprise.ai.runtime.agent;

import com.enterprise.ai.runtime.agent.RuntimeAgentEntity;
import com.enterprise.ai.runtime.agent.RuntimeAgentMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RuntimeAgentToolReferenceServiceTest {

    @Test
    void listsAgentToolReferencesFromActiveWorkflowToolCatalog() {
        RuntimeAgentMapper mapper = mock(RuntimeAgentMapper.class);
        RuntimeAgentConfigService configService = mock(RuntimeAgentConfigService.class);
        RuntimeAgentToolReferenceService service = new RuntimeAgentToolReferenceService(mapper, configService);
        RuntimeAgentEntity agent = agent("agent-1", "Team Assistant");
        when(mapper.selectList(any())).thenReturn(List.of(agent));
        RuntimeAgentConfigVersionEntity active = new RuntimeAgentConfigVersionEntity();
        active.setId(7L);
        active.setAgentId("agent-1");
        when(configService.resolveActive("agent-1")).thenReturn(java.util.Optional.of(active));
        RuntimeAgentWorkflowToolEntity first = workflowTool("orders_create");
        RuntimeAgentWorkflowToolEntity second = workflowTool("orders_cancel");
        when(configService.resolveActiveTools("agent-1", active)).thenReturn(List.of(first, second));

        List<RuntimeAgentToolReferenceService.AgentToolReference> refs = service.listAgentToolReferences();

        assertEquals(1, refs.size());
        assertEquals("agent-1", refs.get(0).agentId());
        assertEquals("Team Assistant", refs.get(0).agentName());
        assertEquals(List.of("orders_create", "orders_cancel"), refs.get(0).tools());
        assertEquals(List.of(), refs.get(0).skills());
    }

    @Test
    void treatsAgentWithoutActiveConfigAsNoReferences() {
        RuntimeAgentMapper mapper = mock(RuntimeAgentMapper.class);
        RuntimeAgentConfigService configService = mock(RuntimeAgentConfigService.class);
        RuntimeAgentToolReferenceService service = new RuntimeAgentToolReferenceService(mapper, configService);
        when(mapper.selectList(any())).thenReturn(List.of(agent("agent-1", "Draft only")));
        when(configService.resolveActive("agent-1")).thenReturn(java.util.Optional.empty());

        List<RuntimeAgentToolReferenceService.AgentToolReference> refs = service.listAgentToolReferences();

        assertEquals(List.of(), refs.get(0).tools());
        assertEquals(List.of(), refs.get(0).skills());
    }

    private RuntimeAgentEntity agent(String id, String name) {
        RuntimeAgentEntity entity = new RuntimeAgentEntity();
        entity.setId(id);
        entity.setName(name);
        return entity;
    }

    private RuntimeAgentWorkflowToolEntity workflowTool(String name) {
        RuntimeAgentWorkflowToolEntity entity = new RuntimeAgentWorkflowToolEntity();
        entity.setToolName(name);
        return entity;
    }
}
