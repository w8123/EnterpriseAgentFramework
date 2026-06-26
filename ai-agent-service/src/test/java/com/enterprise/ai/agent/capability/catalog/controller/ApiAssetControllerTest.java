package com.enterprise.ai.agent.capability.catalog.controller;

import com.enterprise.ai.agent.capability.catalog.controller.ApiAssetController;
import com.enterprise.ai.agent.capability.catalog.scan.ScanModuleEntity;
import com.enterprise.ai.agent.capability.catalog.scan.ScanModuleService;
import com.enterprise.ai.agent.capability.catalog.scan.ScanProjectEntity;
import com.enterprise.ai.agent.capability.catalog.scan.ScanProjectService;
import com.enterprise.ai.agent.capability.catalog.scan.ScanProjectToolEntity;
import com.enterprise.ai.agent.capability.catalog.tool.definition.ToolDefinitionEntity;
import com.enterprise.ai.agent.capability.catalog.tool.definition.ToolDefinitionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ApiAssetControllerTest {

    private ScanProjectService scanProjectService;
    private ScanModuleService scanModuleService;
    private ToolDefinitionService toolDefinitionService;
    private ApiAssetController controller;

    @BeforeEach
    void setUp() {
        scanProjectService = mock(ScanProjectService.class);
        scanModuleService = mock(ScanModuleService.class);
        toolDefinitionService = mock(ToolDefinitionService.class);
        controller = new ApiAssetController(new ObjectMapper(), scanProjectService, scanModuleService, toolDefinitionService);

        ScanProjectEntity teams = project(1L, "qmssmp-teams-construction-service", "班组建设服务", "REGISTERED");
        ScanProjectEntity finance = project(2L, "finance-service", "财务服务", "SCAN");
        when(scanProjectService.list()).thenReturn(List.of(teams, finance));

        ScanModuleEntity teamModule = module(10L, "teams-construction", "运营班组建设");
        when(scanModuleService.listByProject(1L)).thenReturn(List.of(teamModule));
        when(scanModuleService.listByProject(2L)).thenReturn(List.of());

        ScanProjectToolEntity teamArchive = tool(101L, 1L, 10L, "teamArchivePage", "查询班组档案",
                "POST", "/api/v1/teams-construction/team/page", 201L, true, true, false);
        ScanProjectToolEntity payroll = tool(102L, 2L, null, "payrollPage", "查询薪资",
                "GET", "/api/payroll/page", null, false, false, false);
        when(scanProjectService.listTools(1L)).thenReturn(List.of(teamArchive));
        when(scanProjectService.listTools(2L)).thenReturn(List.of(payroll));

        ToolDefinitionEntity globalTool = new ToolDefinitionEntity();
        globalTool.setId(201L);
        globalTool.setName("teamArchivePage");
        globalTool.setQualifiedName("qmssmp-teams-construction-service:teamArchivePage");
        when(toolDefinitionService.mapByIds(java.util.Set.of(201L))).thenReturn(Map.of(201L, globalTool));
        when(toolDefinitionService.mapByIds(java.util.Set.of())).thenReturn(Map.of());
    }

    @Test
    void listsApiAssetsAcrossProjects() {
        ResponseEntity<ApiAssetController.ApiAssetPageResponse> response = controller.list(null, null, null, null,
                null, null, null, null, null, null, null, 1, 20);

        assertNotNull(response.getBody());
        assertEquals(2, response.getBody().total());
        assertEquals("qmssmp-teams-construction-service", response.getBody().items().get(0).projectCode());
        assertEquals("teamArchivePage", response.getBody().items().get(0).name());
        assertEquals("运营班组建设", response.getBody().items().get(0).moduleName());
        assertEquals("teamArchivePage", response.getBody().items().get(0).globalToolName());
        assertEquals("qmssmp-teams-construction-service:teamArchivePage", response.getBody().items().get(0).globalToolQualifiedName());
        assertEquals("managerName", response.getBody().items().get(0).parameters().get(0).name());
    }

    @Test
    void filtersByProjectCodeAndKeyword() {
        ResponseEntity<ApiAssetController.ApiAssetPageResponse> response = controller.list(null,
                "qmssmp-teams-construction-service", null, null, "班组", null, null, null, null, null, null, 1, 20);

        assertNotNull(response.getBody());
        assertEquals(1, response.getBody().total());
        assertEquals("teamArchivePage", response.getBody().items().get(0).name());
    }

    private ScanProjectEntity project(Long id, String code, String name, String kind) {
        ScanProjectEntity entity = new ScanProjectEntity();
        entity.setId(id);
        entity.setProjectCode(code);
        entity.setName(name);
        entity.setProjectKind(kind);
        entity.setEnvironment("dev");
        entity.setVisibility("PROJECT");
        return entity;
    }

    private ScanModuleEntity module(Long id, String name, String displayName) {
        ScanModuleEntity entity = new ScanModuleEntity();
        entity.setId(id);
        entity.setName(name);
        entity.setDisplayName(displayName);
        return entity;
    }

    private ScanProjectToolEntity tool(Long id, Long projectId, Long moduleId, String name, String description,
                                       String method, String path, Long globalToolId,
                                       boolean enabled, boolean agentVisible, boolean removed) {
        ScanProjectToolEntity entity = new ScanProjectToolEntity();
        entity.setId(id);
        entity.setProjectId(projectId);
        entity.setModuleId(moduleId);
        entity.setName(name);
        entity.setDescription(description);
        entity.setParametersJson("[{\"name\":\"managerName\",\"type\":\"string\",\"description\":\"负责人\",\"required\":false,\"location\":\"body\"}]");
        entity.setHttpMethod(method);
        entity.setEndpointPath(path);
        entity.setGlobalToolDefinitionId(globalToolId);
        entity.setEnabled(enabled);
        entity.setAgentVisible(agentVisible);
        entity.setRemovedFromSource(removed);
        return entity;
    }
}
