package com.enterprise.ai.capability.catalog.businessmethod;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.enterprise.ai.agent.registry.RegistryContracts.CapabilityRegistration;
import com.enterprise.ai.capability.catalog.enrichment.CatalogClassificationService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class BusinessMethodCatalogInternalControllerTest {
    @Test void ungroupedSelectionIsAppliedBeforeTheOwningCatalogAndCannotCombineAGroup() {
        var catalog = mock(BusinessMethodCatalogService.class);
        var groups = mock(com.enterprise.ai.capability.catalog.enrichment.CatalogGroupSelection.class);
        var controller = new BusinessMethodCatalogInternalController(catalog, groups);
        var filter = new com.enterprise.ai.capability.catalog.enrichment.CatalogGroupSelection.Filter(7L, null, true);
        when(groups.filter(7L, null, true)).thenReturn(filter);
        when(catalog.pageFiltered(2, 1, "query", true, 7L, filter)).thenReturn(new Page<>(2, 1, 2));
        assertEquals(2L, controller.list(2, 1, "query", true, 7L, null, true).getBody().total());
        verify(catalog).pageFiltered(2, 1, "query", true, 7L, filter);
        assertEquals(HttpStatus.BAD_REQUEST, controller.list(1, 20, null, null, 7L, 4L, true).getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST, controller.list(1, 20, null, null, null, null, true).getStatusCode());
        verifyNoMoreInteractions(catalog);
    }
    @Test void readsOwnerIdentityAndAcceptedSourceThroughTheExistingRoutes() throws Exception {
        assertArrayEquals(new String[]{"/internal/capability/business-methods"},
                BusinessMethodCatalogInternalController.class.getAnnotation(RequestMapping.class).value());
        assertArrayEquals(new String[]{"/{name}"}, BusinessMethodCatalogInternalController.class
                .getDeclaredMethod("get", String.class).getAnnotation(GetMapping.class).value());
        var catalog = mock(BusinessMethodCatalogService.class);
        var groups = mock(com.enterprise.ai.capability.catalog.enrichment.CatalogGroupSelection.class);
        var controller = new BusinessMethodCatalogInternalController(catalog, groups);
        var asset = new BusinessMethodAssetEntity();
        asset.setId(8L); asset.setMethodCode("create"); asset.setInvocationName("orders_create");
        asset.setProjectId(7L); asset.setProjectCode("orders"); asset.setQualifiedName("orders:create");
        asset.setStatus("ACCEPTED"); asset.setEnabled(true);
        var revision = new BusinessMethodRevisionEntity();
        revision.setId(12L); revision.setContractHash("a".repeat(64));
        var declaration = new CapabilityRegistration("create", "创建订单", "创建业务订单", "POST", null, null,
                "/sdk/create", null, null, "WRITE", true, List.of(), Map.of("assetType", "BUSINESS_METHOD"));
        var method = new BusinessMethodDefinition(asset, revision, declaration, "订单系统", "READY");
        var page = new Page<BusinessMethodDefinition>(1, 20, 1); page.setRecords(List.of(method));
        when(catalog.page(1, 20, "order", true, 7L)).thenReturn(page);
        when(catalog.find("orders:create")).thenReturn(Optional.of(method));
        when(groups.labels(7L, "BUSINESS_METHOD", List.of(8L))).thenReturn(Map.of(8L,
                List.of(new CatalogClassificationService.Label(4L, "订单管理"),
                        new CatalogClassificationService.Label(5L, "订单查询"))));
        var list = controller.list(1, 20, "order", true, 7L, null).getBody();
        var detail = controller.get("orders:create").getBody();
        assertNotNull(list); assertNotNull(detail);
        assertEquals(8L, detail.assetId()); assertEquals(12L, detail.acceptedRevisionId());
        assertEquals("BUSINESS_METHOD", list.records().get(0).assetType());
        assertEquals("orders_create", detail.name());
        assertEquals("创建订单", detail.title());
        assertEquals("orders:create", detail.sourceQualifiedName());
        assertEquals(List.of(4L, 5L), list.records().get(0).classifications().stream().map(CatalogClassificationService.Label::id).toList());
        assertEquals("订单管理", detail.classifications().get(0).displayName());
        verify(groups, times(2)).labels(7L, "BUSINESS_METHOD", List.of(8L));
    }
    @Test void toolOnlyRecordsHaveNoMethodDetail() {
        var catalog = mock(BusinessMethodCatalogService.class);
        when(catalog.find("projection_without_method")).thenReturn(Optional.empty());
        assertEquals(HttpStatus.NOT_FOUND, new BusinessMethodCatalogInternalController(catalog, mock(com.enterprise.ai.capability.catalog.enrichment.CatalogGroupSelection.class))
                .get("projection_without_method").getStatusCode());
    }

    @Test void classificationsStayWithinEachProjectEvenWhenPageRowsShareNumericAssetIds() {
        var catalog = mock(BusinessMethodCatalogService.class);
        var groups = mock(com.enterprise.ai.capability.catalog.enrichment.CatalogGroupSelection.class);
        var first = new BusinessMethodAssetEntity();
        first.setId(8L); first.setProjectId(7L); first.setProjectCode("orders"); first.setMethodCode("read");
        first.setInvocationName("orders_read"); first.setQualifiedName("orders:read"); first.setStatus("ACCEPTED");
        var second = new BusinessMethodAssetEntity();
        second.setId(8L); second.setProjectId(9L); second.setProjectCode("billing"); second.setMethodCode("read");
        second.setInvocationName("billing_read"); second.setQualifiedName("billing:read"); second.setStatus("ACCEPTED");
        var revision = new BusinessMethodRevisionEntity(); revision.setId(12L);
        var declaration = new CapabilityRegistration("read", "查询", "", "GET", null, null, "/read", null, null,
                "READ", false, List.of(), Map.of());
        var page = new Page<BusinessMethodDefinition>(1, 20, 2);
        page.setRecords(List.of(new BusinessMethodDefinition(first, revision, declaration, "订单系统", "READY"),
                new BusinessMethodDefinition(second, revision, declaration, "结算系统", "READY")));
        when(catalog.page(1, 20, null, null, null)).thenReturn(page);
        when(groups.labels(7L, "BUSINESS_METHOD", List.of(8L))).thenReturn(Map.of(8L,
                List.of(new CatalogClassificationService.Label(4L, "订单"))));
        when(groups.labels(9L, "BUSINESS_METHOD", List.of(8L))).thenReturn(Map.of(8L,
                List.of(new CatalogClassificationService.Label(9L, "结算"))));
        var controller = new BusinessMethodCatalogInternalController(catalog, groups);
        var response = controller.list(1, 20, null, null, null, null).getBody();
        assertNotNull(response);
        assertEquals("订单", response.records().get(0).classifications().get(0).displayName());
        assertEquals("结算", response.records().get(1).classifications().get(0).displayName());
        verify(groups).labels(7L, "BUSINESS_METHOD", List.of(8L));
        verify(groups).labels(9L, "BUSINESS_METHOD", List.of(8L));

        when(groups.labels(9L, "BUSINESS_METHOD", List.of(8L))).thenThrow(new IllegalStateException("classification unavailable"));
        var degraded = controller.list(1, 20, null, null, null, null).getBody();
        assertNotNull(degraded);
        assertEquals("订单", degraded.records().get(0).classifications().get(0).displayName());
        assertNull(degraded.records().get(1).classifications(), "unknown must not look unclassified");
        when(catalog.find("billing:read")).thenReturn(Optional.of(page.getRecords().get(1)));
        assertNull(controller.get("billing:read").getBody().classifications(), "detail must remain readable when classification fails");
    }


}
