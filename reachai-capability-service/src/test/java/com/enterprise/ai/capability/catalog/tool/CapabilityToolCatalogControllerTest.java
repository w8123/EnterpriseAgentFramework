package com.enterprise.ai.capability.catalog.tool;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.enterprise.ai.agent.capability.catalog.tool.definition.ToolDefinitionEntity;
import com.enterprise.ai.agent.capability.catalog.tool.definition.ToolDefinitionParameter;
import com.enterprise.ai.capability.internal.CapabilityToolExecutionService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CapabilityToolCatalogControllerTest {

    @Test
    void keepsToolCatalogRouteShapeOnCapabilityService() throws Exception {
        RequestMapping controllerMapping = CapabilityToolCatalogController.class.getAnnotation(RequestMapping.class);
        Method list = CapabilityToolCatalogController.class.getDeclaredMethod(
                "list", int.class, int.class, String.class, String.class, Boolean.class, Long.class);
        Method get = CapabilityToolCatalogController.class.getDeclaredMethod("get", String.class);
        Method create = CapabilityToolCatalogController.class.getDeclaredMethod(
                "create", CapabilityToolCatalogController.ToolUpsertRequest.class);
        Method update = CapabilityToolCatalogController.class.getDeclaredMethod(
                "update", String.class, CapabilityToolCatalogController.ToolUpsertRequest.class);
        Method delete = CapabilityToolCatalogController.class.getDeclaredMethod("delete", String.class);
        Method toggle = CapabilityToolCatalogController.class.getDeclaredMethod(
                "toggle", String.class, CapabilityToolCatalogController.ToolToggleRequest.class);
        Method test = CapabilityToolCatalogController.class.getDeclaredMethod(
                "test", String.class, CapabilityToolCatalogController.ToolTestRequest.class);

        assertArrayEquals(new String[] {"/api/tools"}, controllerMapping.value());
        assertArrayEquals(new String[] {}, list.getAnnotation(GetMapping.class).value());
        assertArrayEquals(new String[] {"/{name}"}, get.getAnnotation(GetMapping.class).value());
        assertArrayEquals(new String[] {}, create.getAnnotation(PostMapping.class).value());
        assertArrayEquals(new String[] {"/{name}"}, update.getAnnotation(PutMapping.class).value());
        assertArrayEquals(new String[] {"/{name}"}, delete.getAnnotation(DeleteMapping.class).value());
        assertArrayEquals(new String[] {"/{name}/toggle"}, toggle.getAnnotation(PutMapping.class).value());
        assertArrayEquals(new String[] {"/{name}/test"}, test.getAnnotation(PostMapping.class).value());
    }

    @Test
    void listReturnsOnlyToolKindRecords() {
        CapabilityToolCatalogService service = mock(CapabilityToolCatalogService.class);
        CapabilityToolCatalogController controller = new CapabilityToolCatalogController(
                service, mock(CapabilityToolExecutionService.class));
        ToolDefinitionEntity tool = tool("orders_create", "TOOL");
        ToolDefinitionEntity skill = tool("orders_skill", "SKILL");
        Page<ToolDefinitionEntity> page = new Page<>(1, 20, 2);
        page.setRecords(List.of(tool, skill));
        when(service.page(1, 20, "order", "manual", true, 7L)).thenReturn(page);
        when(service.parseParameters(null)).thenReturn(List.of());

        ResponseEntity<CapabilityToolCatalogController.ToolListPageResponse> response =
                controller.list(1, 20, "order", "manual", true, 7L);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(1, response.getBody().records().size());
        assertEquals("orders_create", response.getBody().records().get(0).name());
    }

    @Test
    void getReturnsNotFoundForMissingTool() {
        CapabilityToolCatalogService service = mock(CapabilityToolCatalogService.class);
        CapabilityToolCatalogController controller = new CapabilityToolCatalogController(
                service, mock(CapabilityToolExecutionService.class));
        when(service.findByName("missing")).thenReturn(java.util.Optional.empty());

        ResponseEntity<CapabilityToolCatalogController.ToolInfoDTO> response = controller.get("missing");

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
    }

    @Test
    void createDelegatesToCapabilityToolCatalogService() {
        CapabilityToolCatalogService service = mock(CapabilityToolCatalogService.class);
        CapabilityToolCatalogController controller = new CapabilityToolCatalogController(
                service, mock(CapabilityToolExecutionService.class));
        CapabilityToolCatalogController.ToolUpsertRequest request = request("orders_create");
        ToolDefinitionEntity created = tool("orders_create", "TOOL");
        when(service.create(any())).thenReturn(created);
        when(service.parseParameters(null)).thenReturn(List.of());

        ResponseEntity<CapabilityToolCatalogController.ToolInfoDTO> response = controller.create(request);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("orders_create", response.getBody().name());
        verify(service).create(any());
    }

    @Test
    void toggleDelegatesToCapabilityToolCatalogService() {
        CapabilityToolCatalogService service = mock(CapabilityToolCatalogService.class);
        CapabilityToolCatalogController controller = new CapabilityToolCatalogController(
                service, mock(CapabilityToolExecutionService.class));
        ToolDefinitionEntity toggled = tool("orders_create", "TOOL");
        toggled.setEnabled(false);
        when(service.toggle("orders_create", false)).thenReturn(toggled);
        when(service.parseParameters(null)).thenReturn(List.of());

        ResponseEntity<CapabilityToolCatalogController.ToolInfoDTO> response =
                controller.toggle("orders_create", new CapabilityToolCatalogController.ToolToggleRequest(false));

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(false, response.getBody().enabled());
        verify(service).toggle("orders_create", false);
    }

    @Test
    void testDelegatesToCapabilityToolExecutionService() {
        CapabilityToolCatalogService service = mock(CapabilityToolCatalogService.class);
        CapabilityToolExecutionService executionService = mock(CapabilityToolExecutionService.class);
        CapabilityToolCatalogController controller = new CapabilityToolCatalogController(service, executionService);
        when(executionService.execute("orders_create", Map.of("input", Map.of("orderId", "A-1"))))
                .thenReturn(Map.of("success", true, "data", Map.of("status", "ok")));

        ResponseEntity<CapabilityToolCatalogController.ToolTestResult> response = controller.test(
                "orders_create",
                new CapabilityToolCatalogController.ToolTestRequest(Map.of("orderId", "A-1")));

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(true, response.getBody().success());
        assertEquals("{status=ok}", response.getBody().result());
        assertEquals(null, response.getBody().errorMessage());
        verify(executionService).execute("orders_create", Map.of("input", Map.of("orderId", "A-1")));
    }

    private ToolDefinitionEntity tool(String name, String kind) {
        ToolDefinitionEntity entity = new ToolDefinitionEntity();
        entity.setId(11L);
        entity.setName(name);
        entity.setKind(kind);
        entity.setDescription("Create order");
        entity.setParametersJson(null);
        entity.setSource("manual");
        entity.setHttpMethod("POST");
        entity.setEndpointPath("/create");
        entity.setEnabled(true);
        entity.setAgentVisible(true);
        entity.setLightweightEnabled(false);
        return entity;
    }

    private CapabilityToolCatalogController.ToolUpsertRequest request(String name) {
        return new CapabilityToolCatalogController.ToolUpsertRequest(
                name,
                "Create order",
                List.of(new ToolDefinitionParameter("orderId", "string", "Order id", true, "body")),
                "manual",
                "manual",
                "POST",
                "http://orders.local",
                "/orders",
                "/create",
                "JSON",
                "JSON",
                7L,
                "orders",
                "PROJECT",
                "orders:createOrder",
                true,
                true,
                false
        );
    }
}
