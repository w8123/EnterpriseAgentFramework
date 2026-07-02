package com.enterprise.ai.capability.catalog.scan;

import com.enterprise.ai.agent.capability.catalog.scan.ScanProjectAgentReferenceReader;
import com.enterprise.ai.agent.capability.catalog.scan.ScanProjectBlockers;
import com.enterprise.ai.agent.capability.catalog.tool.definition.ToolDefinitionEntity;
import com.enterprise.ai.agent.capability.catalog.tool.definition.ToolDefinitionMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CapabilityScanProjectBlockerServiceTest {

    @Test
    void reportsAgentsReferencingProjectOwnedToolsAndSkills() {
        ToolDefinitionMapper toolDefinitionMapper = mock(ToolDefinitionMapper.class);
        ScanProjectAgentReferenceReader referenceReader = mock(ScanProjectAgentReferenceReader.class);
        CapabilityScanProjectBlockerService service =
                new CapabilityScanProjectBlockerService(toolDefinitionMapper, referenceReader);
        when(toolDefinitionMapper.selectList(any())).thenReturn(List.of(
                tool("orders_create", "TOOL"),
                tool("orders_skill", "SKILL")
        ));
        when(referenceReader.listAgentToolReferences()).thenReturn(List.of(
                new ScanProjectAgentReferenceReader.AgentToolReference(
                        "agent-1",
                        "Team Assistant",
                        List.of("orders_create"),
                        List.of("orders_skill"))
        ));

        ScanProjectBlockers blockers = service.analyze(7L);

        assertTrue(blockers.blocked());
        assertEquals(List.of("orders_create"), blockers.tools());
        assertEquals(List.of("orders_skill"), blockers.skills());
        assertEquals(List.of(new ScanProjectBlockers.AgentRef("agent-1", "Team Assistant")), blockers.agents());
    }

    private ToolDefinitionEntity tool(String name, String kind) {
        ToolDefinitionEntity entity = new ToolDefinitionEntity();
        entity.setName(name);
        entity.setKind(kind);
        return entity;
    }
}
