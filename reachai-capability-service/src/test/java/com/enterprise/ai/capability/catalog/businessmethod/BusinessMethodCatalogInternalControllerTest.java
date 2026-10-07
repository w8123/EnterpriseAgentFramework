package com.enterprise.ai.capability.catalog.businessmethod;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.enterprise.ai.agent.registry.RegistryContracts.CapabilityRegistration;
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
    @Test void readsOwnerIdentityAndAcceptedSourceThroughTheExistingRoutes() throws Exception {
        assertArrayEquals(new String[]{"/internal/capability/business-methods"},
                BusinessMethodCatalogInternalController.class.getAnnotation(RequestMapping.class).value());
        assertArrayEquals(new String[]{"/{name}"}, BusinessMethodCatalogInternalController.class
                .getDeclaredMethod("get", String.class).getAnnotation(GetMapping.class).value());
        var catalog = mock(BusinessMethodCatalogService.class);
        var controller = new BusinessMethodCatalogInternalController(catalog);
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
        var list = controller.list(1, 20, "order", true, 7L).getBody();
        var detail = controller.get("orders:create").getBody();
        assertNotNull(list); assertNotNull(detail);
        assertEquals(8L, detail.assetId()); assertEquals(12L, detail.acceptedRevisionId());
        assertEquals("BUSINESS_METHOD", list.records().get(0).assetType());
        assertEquals("orders_create", detail.name());
        assertEquals("创建订单", detail.title());
        assertEquals("orders:create", detail.sourceQualifiedName());
    }
    @Test void toolOnlyRecordsHaveNoMethodDetail() {
        var catalog = mock(BusinessMethodCatalogService.class);
        when(catalog.find("projection_without_method")).thenReturn(Optional.empty());
        assertEquals(HttpStatus.NOT_FOUND, new BusinessMethodCatalogInternalController(catalog)
                .get("projection_without_method").getStatusCode());
    }

    @Test void summaryUsesTheOwnerAggregateWithoutListingOrHydratingDefinitions() throws Exception {
        assertArrayEquals(new String[]{"/summary"}, BusinessMethodCatalogInternalController.class
                .getDeclaredMethod("summary", Long.class).getAnnotation(GetMapping.class).value());
        var catalog = mock(BusinessMethodCatalogService.class);
        var summary = new BusinessMethodCatalogSummary(8, 6, 2);
        when(catalog.summary(7L)).thenReturn(summary);
        assertEquals(summary, new BusinessMethodCatalogInternalController(catalog).summary(7L).getBody());
        verify(catalog).summary(7L);
        verifyNoMoreInteractions(catalog);
    }
}
