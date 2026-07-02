package com.enterprise.ai.runtime.agent;

import com.enterprise.ai.runtime.workflow.RuntimeAgentEntryEntity;
import com.enterprise.ai.runtime.workflow.RuntimeAgentEntryMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RuntimeAgentToolReferenceServiceTest {

    @Test
    void listsAgentToolReferencesFromEntryConfigJson() {
        RuntimeAgentEntryMapper mapper = mock(RuntimeAgentEntryMapper.class);
        RuntimeAgentToolReferenceService service = new RuntimeAgentToolReferenceService(mapper);
        RuntimeAgentEntryEntity agent = agent(
                "agent-1",
                "Team Assistant",
                "{\"tools\":[\"orders_create\",\" orders_cancel \"],\"skills\":[\"orders_skill\"]}");
        when(mapper.selectList(any())).thenReturn(List.of(agent));

        List<RuntimeAgentToolReferenceService.AgentToolReference> refs = service.listAgentToolReferences();

        assertEquals(1, refs.size());
        assertEquals("agent-1", refs.get(0).agentId());
        assertEquals("Team Assistant", refs.get(0).agentName());
        assertEquals(List.of("orders_create", "orders_cancel"), refs.get(0).tools());
        assertEquals(List.of("orders_skill"), refs.get(0).skills());
    }

    @Test
    void treatsInvalidOrBlankConfigAsNoReferences() {
        RuntimeAgentEntryMapper mapper = mock(RuntimeAgentEntryMapper.class);
        RuntimeAgentToolReferenceService service = new RuntimeAgentToolReferenceService(mapper);
        when(mapper.selectList(any())).thenReturn(List.of(agent("agent-1", "Broken", "{")));

        List<RuntimeAgentToolReferenceService.AgentToolReference> refs = service.listAgentToolReferences();

        assertEquals(List.of(), refs.get(0).tools());
        assertEquals(List.of(), refs.get(0).skills());
    }

    private RuntimeAgentEntryEntity agent(String id, String name, String entryConfigJson) {
        RuntimeAgentEntryEntity entity = new RuntimeAgentEntryEntity();
        entity.setId(id);
        entity.setName(name);
        entity.setEntryConfigJson(entryConfigJson);
        return entity;
    }
}
