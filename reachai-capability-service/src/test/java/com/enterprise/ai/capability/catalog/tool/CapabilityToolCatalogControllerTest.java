package com.enterprise.ai.capability.catalog.tool;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.enterprise.ai.agent.capability.catalog.tool.definition.ToolDefinitionEntity;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CapabilityToolCatalogControllerTest {

    @Test
    void keepsToolCatalogRouteShapeOnCapabilityService() throws Exception {
        RequestMapping controllerMapping = CapabilityToolCatalogController.class.getAnnotation(RequestMapping.class);
        Method list = CapabilityToolCatalogController.class.getDeclaredMethod(
                "list", int.class, int.class, String.class, String.class, Boolean.class, Long.class);
        Method get = CapabilityToolCatalogController.class.getDeclaredMethod("get", String.class);

        assertArrayEquals(new String[] {"/api/tools"}, controllerMapping.value());
        assertArrayEquals(new String[] {}, list.getAnnotation(GetMapping.class).value());
        assertArrayEquals(new String[] {"/{name}"}, get.getAnnotation(GetMapping.class).value());
    }

    @Test
    void listReturnsToolRecords() {
        CapabilityToolCatalogService service = mock(CapabilityToolCatalogService.class);
        CapabilityToolCatalogController controller = new CapabilityToolCatalogController(service, mock(com.enterprise.ai.capability.internal.CapabilitySourceContractGuard.class));
        ToolDefinitionEntity tool = tool("orders_create");
        Page<ToolDefinitionEntity> page = new Page<>(1, 20, 1);
        page.setRecords(List.of(tool));
        when(service.page(1, 20, "order", "manual", true, 7L)).thenReturn(page);
        when(service.parseParameters(null)).thenReturn(List.of());

        ResponseEntity<CapabilityToolCatalogController.ToolListPageResponse> response =
                controller.list(1, 20, "order", "manual", true, 7L);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(1, response.getBody().records().size());
        assertEquals("orders_create", response.getBody().records().get(0).name());
        assertEquals("创建订单", response.getBody().records().get(0).title());
    }

    @Test
    void getReturnsNotFoundForMissingTool() {
        CapabilityToolCatalogService service = mock(CapabilityToolCatalogService.class);
        CapabilityToolCatalogController controller = new CapabilityToolCatalogController(service, mock(com.enterprise.ai.capability.internal.CapabilitySourceContractGuard.class));
        when(service.findByName("missing")).thenReturn(java.util.Optional.empty());

        ResponseEntity<CapabilityToolCatalogController.ToolInfoDTO> response = controller.get("missing");

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
    }

    private ToolDefinitionEntity tool(String name) {
        ToolDefinitionEntity entity = new ToolDefinitionEntity();
        entity.setId(11L);
        entity.setName(name);
        entity.setTitle("创建订单");
        entity.setDescription("Create order");
        entity.setParametersJson(null);
        entity.setSource("manual");
        entity.setHttpMethod("POST");
        entity.setEndpointPath("/create");
        entity.setEnabled(true);
        return entity;
    }

}
