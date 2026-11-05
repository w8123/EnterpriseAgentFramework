package com.enterprise.ai.control.pageworkbench.application;

import com.enterprise.ai.control.pageworkbench.application.PageWorkbenchContract.ActionInput;
import com.enterprise.ai.control.pageworkbench.application.PageWorkbenchContract.ManualPageCommand;
import com.enterprise.ai.control.pageworkbench.application.PageWorkbenchContract.PageReport;
import com.enterprise.ai.control.pageworkbench.persistence.PageActionEntity;
import com.enterprise.ai.control.pageworkbench.persistence.PageActionMapper;
import com.enterprise.ai.control.pageworkbench.persistence.PageResourceMapper;
import com.enterprise.ai.control.pageworkbench.persistence.ProjectPageEntity;
import com.enterprise.ai.control.pageworkbench.persistence.ProjectPageMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class PageCatalogApplicationServiceTest {

    @Test
    void sdkReportsCannotClearOrEraseAnExistingConfirmationFloor() {
        var pageMapper = mock(ProjectPageMapper.class);
        var resources = mock(PageResourceMapper.class);
        var actions = mock(PageActionMapper.class);
        var service = new PageCatalogApplicationService(pageMapper, resources, actions, new ObjectMapper(),
                mock(PageWorkbenchProjectPort.class));
        var page = new ProjectPageEntity();
        page.setId(41L); page.setProjectId(7L); page.setProjectCode("orders"); page.setPageKey("orders.list");
        page.setSourceType("SDK"); page.setLifecycleStatus("ACTIVE");
        var action = new PageActionEntity();
        action.setId(91L); action.setPageId(41L); action.setProjectId(7L); action.setProjectCode("orders");
        action.setPageKey("orders.list"); action.setActionKey("change"); action.setSourceType("SDK");
        action.setStatus("ACTIVE"); action.setConfirmRequired(true); action.setRiskLevel("WRITE");
        action.setAllowedAgentIdsJson("[\"trusted-agent\"]");
        when(pageMapper.selectOne(any())).thenReturn(page);
        when(pageMapper.selectById(41L)).thenReturn(page);
        when(actions.selectOne(any())).thenReturn(action);
        when(actions.selectList(any())).thenReturn(List.of(action));
        when(resources.selectList(any())).thenReturn(List.of());
        var noConfirmation = new ActionInput("change", "Change", null, "PAGE_ACTION", "READ", false,
                null, null, null, null, List.of(), null, null);
        service.upsertPageCatalog(7L, "orders", new PageReport("orders.list", null, null, "Orders", null,
                "/orders", null, null, null, List.of(noConfirmation)), com.enterprise.ai.control.pageworkbench.domain.PageWorkbenchValues.PageSource.SDK, false);
        assertEquals(Boolean.TRUE, action.getConfirmRequired());
        assertEquals("WRITE", action.getRiskLevel());
        assertEquals("[\"trusted-agent\"]", action.getAllowedAgentIdsJson());
        service.upsertPageCatalog(7L, "orders", new PageReport("orders.list", null, null, "Orders", null,
                "/orders", null, null, null, List.of()), com.enterprise.ai.control.pageworkbench.domain.PageWorkbenchValues.PageSource.SDK, true);
        assertEquals("INACTIVE", action.getStatus());
        verify(actions, never()).deleteById(91L);
        service.upsertPageCatalog(7L, "orders", new PageReport("orders.list", null, null, "Orders", null,
                "/orders", null, null, null, List.of(noConfirmation)), com.enterprise.ai.control.pageworkbench.domain.PageWorkbenchValues.PageSource.SDK, false);
        assertEquals("INACTIVE", action.getStatus());
        assertEquals(Boolean.TRUE, action.getConfirmRequired());
        assertEquals("[\"trusted-agent\"]", action.getAllowedAgentIdsJson());
    }

    @Test
    void rejectsNonHttpBusinessPageUrl() {
        ProjectPageMapper pageMapper = mock(ProjectPageMapper.class);
        PageResourceMapper resourceMapper = mock(PageResourceMapper.class);
        PageActionMapper actionMapper = mock(PageActionMapper.class);
        PageWorkbenchProjectPort capabilityClient =
                mock(PageWorkbenchProjectPort.class);
        when(capabilityClient.getProjectById(7L))
                .thenReturn(Map.of("id", 7L, "projectCode", "orders"));
        PageCatalogApplicationService service = new PageCatalogApplicationService(
                pageMapper,
                resourceMapper,
                actionMapper,
                new ObjectMapper(),
                capabilityClient);

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> service.createManualPage(
                        "orders",
                        new ManualPageCommand(
                                7L,
                                "orders.list",
                                "orders",
                                "订单",
                                "订单列表",
                                null,
                                "/orders",
                                "javascript:alert(1)",
                                "src/views/orders/List.vue",
                                List.of(),
                                List.of())));

        assertEquals(
                "businessPageUrl must be an absolute HTTP(S) business page URL",
                error.getMessage());
        verify(pageMapper, never()).insert(any());
    }

    @Test
    void rejectsAProjectIdThatDoesNotMatchTheRouteProjectCode() {
        ProjectPageMapper pageMapper = mock(ProjectPageMapper.class);
        PageResourceMapper resourceMapper = mock(PageResourceMapper.class);
        PageActionMapper actionMapper = mock(PageActionMapper.class);
        PageWorkbenchProjectPort capabilityClient =
                mock(PageWorkbenchProjectPort.class);
        when(capabilityClient.getProjectById(7L))
                .thenReturn(Map.of("id", 7L, "projectCode", "inventory"));
        PageCatalogApplicationService service = new PageCatalogApplicationService(
                pageMapper,
                resourceMapper,
                actionMapper,
                new ObjectMapper(),
                capabilityClient);

        assertThrows(IllegalArgumentException.class, () -> service.createManualPage(
                "orders",
                new ManualPageCommand(
                        7L,
                        "orders.list",
                        "orders",
                        "订单",
                        "订单列表",
                        null,
                        "/orders",
                        null,
                        "src/views/orders/List.vue",
                        List.of(),
                        List.of())));

        verifyNoInteractions(pageMapper, resourceMapper, actionMapper);
    }

    @Test
    void scanCannotOverwriteManualPageOrActionContracts() {
        ProjectPageMapper pageMapper = mock(ProjectPageMapper.class);
        PageResourceMapper resourceMapper = mock(PageResourceMapper.class);
        PageActionMapper actionMapper = mock(PageActionMapper.class);
        PageWorkbenchProjectPort capabilityClient =
                mock(PageWorkbenchProjectPort.class);
        PageCatalogApplicationService service = new PageCatalogApplicationService(
                pageMapper,
                resourceMapper,
                actionMapper,
                new ObjectMapper(),
                capabilityClient);
        ProjectPageEntity page = new ProjectPageEntity();
        page.setId(41L);
        page.setProjectId(7L);
        page.setProjectCode("orders");
        page.setPageKey("orders.list");
        page.setName("人工确认的订单页");
        page.setSourceType("MANUAL");
        page.setLifecycleStatus("ACTIVE");
        when(pageMapper.selectOne(any())).thenReturn(page);
        PageActionEntity manualAction = new PageActionEntity();
        manualAction.setId(91L);
        manualAction.setPageId(41L);
        manualAction.setActionKey("search");
        manualAction.setSourceType("MANUAL");
        when(actionMapper.selectList(any())).thenReturn(List.of(manualAction));
        when(actionMapper.selectOne(any())).thenReturn(manualAction);

        service.applyPageMap(
                7L,
                "orders",
                "INCREMENTAL",
                LocalDateTime.of(2026, 7, 25, 10, 0),
                List.of(new PageReport(
                        "orders.list",
                        "orders",
                        "订单",
                        "扫描名称",
                        null,
                        "/orders",
                        null,
                        "src/views/orders/List.vue",
                        List.of(),
                        List.of(new ActionInput(
                                "search",
                                "扫描动作",
                                null,
                                "PAGE_ACTION",
                                "READ",
                                false,
                                null,
                                null,
                                null,
                                null,
                                List.of(),
                                null,
                                null)))));

        ArgumentCaptor<ProjectPageEntity> pageCaptor =
                ArgumentCaptor.forClass(ProjectPageEntity.class);
        verify(pageMapper).updateById(pageCaptor.capture());
        assertEquals("人工确认的订单页", pageCaptor.getValue().getName());
        assertEquals("MANUAL", pageCaptor.getValue().getSourceType());
        assertEquals(LocalDateTime.of(2026, 7, 25, 10, 0),
                pageCaptor.getValue().getLastDiscoveredAt());
        verify(actionMapper, never()).deleteById(91L);
        verify(actionMapper, never()).updateById(manualAction);
    }
}
