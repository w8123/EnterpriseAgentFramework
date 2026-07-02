package com.enterprise.ai.capability.catalog.retrieval;

import com.enterprise.ai.agent.capability.catalog.tool.definition.ToolDefinitionEntity;
import com.enterprise.ai.agent.capability.catalog.tool.definition.ToolDefinitionMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CapabilityToolRetrievalServiceTest {

    private final ToolDefinitionMapper toolDefinitionMapper = mock(ToolDefinitionMapper.class);
    private final CapabilityToolRetrievalService service = new CapabilityToolRetrievalService(toolDefinitionMapper);

    @Test
    void keywordSearchReturnsMatchingEnabledVisibleTools() {
        when(toolDefinitionMapper.selectList(any())).thenReturn(List.of(
                tool(1L, "order.create", "创建订单", "面向订单提交", 7L, 2L, true, true),
                tool(2L, "user.delete", "删除用户", "高危操作", 7L, 2L, true, true),
                tool(3L, "order.hidden", "创建订单草稿", "内部使用", 7L, 2L, true, false)
        ));

        List<CapabilityToolCandidate> candidates = service.retrieve(
                "订单",
                new CapabilityRetrievalScope(List.of(7L), List.of(2L), null, true, true),
                5,
                0.0);

        assertEquals(1, candidates.size());
        assertEquals("order.create", candidates.get(0).toolName());
        assertEquals(1L, candidates.get(0).toolId());
        assertEquals(7L, candidates.get(0).projectId());
        assertEquals(2L, candidates.get(0).moduleId());
    }

    @Test
    void honorsTopK() {
        when(toolDefinitionMapper.selectList(any())).thenReturn(List.of(
                tool(1L, "order.create", "订单创建", "create order", null, null, true, true),
                tool(2L, "order.query", "订单查询", "query order", null, null, true, true)
        ));

        List<CapabilityToolCandidate> candidates = service.retrieve(
                "订单",
                new CapabilityRetrievalScope(null, null, null, true, true),
                1,
                0.0);

        assertEquals(1, candidates.size());
    }

    private ToolDefinitionEntity tool(Long id,
                                      String name,
                                      String description,
                                      String aiDescription,
                                      Long projectId,
                                      Long moduleId,
                                      boolean enabled,
                                      boolean agentVisible) {
        ToolDefinitionEntity tool = new ToolDefinitionEntity();
        tool.setId(id);
        tool.setName(name);
        tool.setDescription(description);
        tool.setAiDescription(aiDescription);
        tool.setProjectId(projectId);
        tool.setModuleId(moduleId);
        tool.setEnabled(enabled);
        tool.setAgentVisible(agentVisible);
        return tool;
    }
}
