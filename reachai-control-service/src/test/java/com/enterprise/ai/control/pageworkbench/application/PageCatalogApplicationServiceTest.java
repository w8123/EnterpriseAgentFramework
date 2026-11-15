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
    void reviewedGenerationChangesForRouteAbaButNotIdenticalSdkReports() {
        var pages = mock(ProjectPageMapper.class);
        var actions = mock(PageActionMapper.class);
        var resources = mock(PageResourceMapper.class);
        var generations = mock(com.enterprise.ai.control.pageworkbench.persistence.PageActionEffectGenerationMapper.class);
        var service = new PageCatalogApplicationService(pages, resources, actions, new ObjectMapper(), mock(PageWorkbenchProjectPort.class));
        service.setEffectGenerations(generations);
        var page = new ProjectPageEntity();
        page.setId(1L); page.setProjectId(7L); page.setProjectCode("mall"); page.setPageKey("profile");
        page.setName("Profile"); page.setRoutePattern("/a"); page.setSourceType("SDK"); page.setLifecycleStatus("ACTIVE");
        var action = new PageActionEntity();
        action.setId(2L); action.setPageId(1L); action.setProjectId(7L); action.setProjectCode("mall"); action.setPageKey("profile");
        action.setActionKey("fill"); action.setTitle("Fill"); action.setActionType("PAGE_ACTION"); action.setRiskLevel("READ");
        action.setConfirmRequired(false); action.setSourceType("SDK"); action.setStatus("ACTIVE");
        action.setInputSchemaJson("{}"); action.setOutputSchemaJson("{}"); action.setSampleArgsJson("{}"); action.setAllowedAgentIdsJson("[]");
        when(pages.selectOne(any())).thenReturn(page);
        when(actions.selectOne(any())).thenReturn(action);
        when(actions.selectList(any())).thenReturn(List.of(action));
        when(resources.selectList(any())).thenReturn(List.of());
        var input = new ActionInput("fill", "Fill", null, "PAGE_ACTION", "READ", false,
                null, null, null, null, List.of(), null, null);
        for (String route : List.of("/a", "/b", "/a")) {
            service.upsertPageCatalog(7L, "mall", new PageReport("profile", null, null, "Profile", null,
                    route, null, null, null, List.of(input)), com.enterprise.ai.control.pageworkbench.domain.PageWorkbenchValues.PageSource.SDK, false);
        }
        verify(generations, org.mockito.Mockito.times(2)).invalidatePage(1L);
        verify(generations, never()).invalidateAction(2L);
    }

    @Test
    void actionLookupRejectsCaseFoldedOrInconsistentDatabaseRows() {
        var pages = mock(ProjectPageMapper.class);
        var actions = mock(PageActionMapper.class);
        var service = new PageCatalogApplicationService(pages, mock(PageResourceMapper.class), actions,
                new ObjectMapper(), mock(PageWorkbenchProjectPort.class));
        var page = new ProjectPageEntity();
        page.setId(1L); page.setProjectCode("mall"); page.setPageKey("profile"); page.setLifecycleStatus("ACTIVE");
        var action = new PageActionEntity();
        action.setPageId(1L); action.setProjectCode("mall"); action.setPageKey("profile");
        action.setActionKey("profile.fill"); action.setStatus("ACTIVE");
        when(pages.selectOne(any())).thenReturn(page);
        when(pages.selectById(1L)).thenReturn(page);
        when(actions.selectOne(any())).thenReturn(action);
        assertEquals(true, service.findAction(" mall ", " profile ", " profile.fill ").isPresent());
        assertEquals(true, service.findActiveActionForUpdate(" mall ", " profile ", " profile.fill ").isPresent());
        for (List<String> mismatched : List.of(
                List.of("MALL", "profile", "profile.fill"),
                List.of("mall", "PROFILE", "profile.fill"),
                List.of("mall", "profile", "PROFILE.FILL"),
                List.of("other-project", "profile", "profile.fill"))) {
            assertEquals(true, service.findAction(mismatched.get(0), mismatched.get(1), mismatched.get(2)).isEmpty());
            assertEquals(true, service.findActiveActionForUpdate(mismatched.get(0), mismatched.get(1), mismatched.get(2)).isEmpty());
        }
        action.setProjectCode("MALL");
        assertEquals(true, service.findActiveActionForUpdate("mall", "profile", "profile.fill").isEmpty());
        action.setProjectCode("mall"); action.setPageKey("PROFILE");
        assertEquals(true, service.findActiveActionForUpdate("mall", "profile", "profile.fill").isEmpty());
        action.setPageKey("profile"); action.setPageId(2L);
        assertEquals(true, service.findAction("mall", "profile", "profile.fill").isEmpty());
        assertEquals(true, service.findActiveActionForUpdate("mall", "profile", "profile.fill").isEmpty());
        action.setPageId(1L); page.setProjectCode("MALL");
        assertEquals(true, service.findAction("mall", "profile", "profile.fill").isEmpty());
        page.setProjectCode("mall"); page.setPageKey("PROFILE");
        assertEquals(true, service.findAction("mall", "profile", "profile.fill").isEmpty());
    }

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
