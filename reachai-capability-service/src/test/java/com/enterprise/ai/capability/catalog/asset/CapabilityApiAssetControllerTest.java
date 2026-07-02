package com.enterprise.ai.capability.catalog.asset;

import com.enterprise.ai.agent.capability.catalog.scan.ScanModuleEntity;
import com.enterprise.ai.agent.capability.catalog.scan.ScanModuleMapper;
import com.enterprise.ai.agent.capability.catalog.scan.ScanProjectEntity;
import com.enterprise.ai.agent.capability.catalog.scan.ScanProjectMapper;
import com.enterprise.ai.agent.capability.catalog.scan.ScanProjectToolEntity;
import com.enterprise.ai.agent.capability.catalog.scan.ScanProjectToolMapper;
import com.enterprise.ai.agent.capability.catalog.tool.definition.ToolDefinitionEntity;
import com.enterprise.ai.agent.capability.catalog.tool.definition.ToolDefinitionMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CapabilityApiAssetControllerTest {

    private final ScanProjectMapper scanProjectMapper = mock(ScanProjectMapper.class);
    private final ScanModuleMapper scanModuleMapper = mock(ScanModuleMapper.class);
    private final ScanProjectToolMapper scanProjectToolMapper = mock(ScanProjectToolMapper.class);
    private final ToolDefinitionMapper toolDefinitionMapper = mock(ToolDefinitionMapper.class);
    private final CapabilityApiAssetController controller = new CapabilityApiAssetController(
            new ObjectMapper(),
            scanProjectMapper,
            scanModuleMapper,
            scanProjectToolMapper,
            toolDefinitionMapper);

    @Test
    void listsApiAssetsWithBatchedModuleAndToolQueriesAcrossProjects() {
        when(scanProjectMapper.selectList(any())).thenReturn(List.of(
                project(1L, "orders"),
                project(2L, "billing")));
        when(scanModuleMapper.selectList(any())).thenReturn(List.of(
                module(11L, 1L, "order-api"),
                module(21L, 2L, "billing-api")));
        when(scanProjectToolMapper.selectList(any())).thenReturn(List.of(
                tool(101L, 1L, 11L, 1001L, "queryOrder"),
                tool(201L, 2L, 21L, 2001L, "queryBill")));
        when(toolDefinitionMapper.selectBatchIds(anyCollection())).thenReturn(List.of(
                globalTool(1001L, "globalQueryOrder"),
                globalTool(2001L, "globalQueryBill")));

        CapabilityApiAssetController.ApiAssetPageResponse response = controller.list(
                null, null, null, null, "query", null,
                null, null, null, null, null,
                1, 20).getBody();

        assertEquals(2, response.total());
        assertEquals(List.of("queryOrder", "queryBill"),
                response.items().stream().map(CapabilityApiAssetController.ApiAssetItem::name).toList());
        verify(scanModuleMapper, times(1)).selectList(any());
        verify(scanProjectToolMapper, times(1)).selectList(any());
    }

    @Test
    void usesDatabasePaginationForUnfilteredList() {
        when(scanProjectMapper.selectList(any())).thenReturn(List.of(project(1L, "orders")));
        when(scanModuleMapper.selectList(any())).thenReturn(List.of(module(11L, 1L, "order-api")));
        ScanProjectToolEntity tool = tool(101L, 1L, 11L, 1001L, "queryOrder");
        when(scanProjectToolMapper.selectCount(any())).thenReturn(99L);
        when(scanProjectToolMapper.selectList(any())).thenReturn(List.of(tool));
        when(toolDefinitionMapper.selectBatchIds(anyCollection())).thenReturn(List.of(globalTool(1001L, "globalQueryOrder")));

        CapabilityApiAssetController.ApiAssetPageResponse response = controller.list(
                null, null, null, null, null, null,
                null, null, null, null, null,
                1, 20).getBody();

        assertEquals(99, response.total());
        assertEquals(1, response.items().size());
        verify(scanProjectToolMapper, times(1)).selectCount(any());
        verify(scanProjectToolMapper, times(1)).selectList(any());
    }

    private ScanProjectEntity project(Long id, String code) {
        ScanProjectEntity entity = new ScanProjectEntity();
        entity.setId(id);
        entity.setName(code + "-project");
        entity.setProjectCode(code);
        entity.setProjectKind("REGISTERED");
        return entity;
    }

    private ScanModuleEntity module(Long id, Long projectId, String name) {
        ScanModuleEntity entity = new ScanModuleEntity();
        entity.setId(id);
        entity.setProjectId(projectId);
        entity.setName(name);
        return entity;
    }

    private ScanProjectToolEntity tool(Long id, Long projectId, Long moduleId, Long globalToolId, String name) {
        ScanProjectToolEntity entity = new ScanProjectToolEntity();
        entity.setId(id);
        entity.setProjectId(projectId);
        entity.setModuleId(moduleId);
        entity.setGlobalToolDefinitionId(globalToolId);
        entity.setName(name);
        entity.setEnabled(true);
        entity.setAgentVisible(true);
        return entity;
    }

    private ToolDefinitionEntity globalTool(Long id, String name) {
        ToolDefinitionEntity entity = new ToolDefinitionEntity();
        entity.setId(id);
        entity.setName(name);
        entity.setQualifiedName("global:" + name);
        return entity;
    }
}
