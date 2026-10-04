package com.enterprise.ai.capability.catalog.businessmethod;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.enterprise.ai.agent.capability.catalog.tool.definition.ToolDefinitionEntity;
import com.enterprise.ai.capability.catalog.tool.CapabilityToolCatalogService;
import com.enterprise.ai.capability.internal.CapabilitySourceContractGuard;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BusinessMethodCatalogInternalControllerTest {

    @Test
    void keepsTheCapabilityOwnedBusinessMethodReadRouteShape() throws Exception {
        RequestMapping mapping = BusinessMethodCatalogInternalController.class.getAnnotation(RequestMapping.class);
        Method list = BusinessMethodCatalogInternalController.class.getDeclaredMethod(
                "list", int.class, int.class, String.class, Boolean.class, Long.class);
        Method get = BusinessMethodCatalogInternalController.class.getDeclaredMethod("get", String.class);

        assertArrayEquals(new String[] {"/internal/capability/business-methods"}, mapping.value());
        assertArrayEquals(new String[] {}, list.getAnnotation(GetMapping.class).value());
        assertArrayEquals(new String[] {"/{name}"}, get.getAnnotation(GetMapping.class).value());
    }

    @Test
    void listsAndReadsOnlyBusinessMethodProjectionRecords() {
        CapabilityToolCatalogService service = mock(CapabilityToolCatalogService.class);
        CapabilitySourceContractGuard sourceGuard = mock(CapabilitySourceContractGuard.class);
        BusinessMethodCatalogInternalController controller =
                new BusinessMethodCatalogInternalController(service, sourceGuard);
        ToolDefinitionEntity businessMethod = businessMethod("orders_create");
        Page<ToolDefinitionEntity> page = new Page<>(1, 20, 1);
        page.setRecords(List.of(businessMethod));
        when(service.pageBusinessMethods(1, 20, "order", true, 7L)).thenReturn(page);
        when(service.parseParameters(null)).thenReturn(List.of());
        when(service.findBusinessMethodByName("orders_create")).thenReturn(Optional.of(businessMethod));

        ResponseEntity<BusinessMethodCatalogInternalController.BusinessMethodPageResponse> list =
                controller.list(1, 20, "order", true, 7L);
        ResponseEntity<BusinessMethodCatalogInternalController.BusinessMethodInfoDTO> detail =
                controller.get("orders_create");

        assertEquals(HttpStatus.OK, list.getStatusCode());
        assertEquals("BUSINESS_METHOD", list.getBody().records().get(0).assetType());
        assertEquals(HttpStatus.OK, detail.getStatusCode());
        assertEquals("orders_create", detail.getBody().name());
        assertEquals("BUSINESS_METHOD", detail.getBody().assetType());
    }

    @Test
    void returnsNotFoundWhenTheNameIsNotAStoredBusinessMethod() {
        CapabilityToolCatalogService service = mock(CapabilityToolCatalogService.class);
        BusinessMethodCatalogInternalController controller = new BusinessMethodCatalogInternalController(
                service, mock(CapabilitySourceContractGuard.class));
        when(service.findBusinessMethodByName("legacy_http_api")).thenReturn(Optional.empty());

        assertEquals(HttpStatus.NOT_FOUND, controller.get("legacy_http_api").getStatusCode());
    }

    private ToolDefinitionEntity businessMethod(String name) {
        ToolDefinitionEntity entity = new ToolDefinitionEntity();
        entity.setName(name);
        entity.setAssetType("BUSINESS_METHOD");
        entity.setTitle("创建订单");
        entity.setDescription("Create order");
        entity.setEnabled(true);
        entity.setSource("sdk");
        return entity;
    }
}
