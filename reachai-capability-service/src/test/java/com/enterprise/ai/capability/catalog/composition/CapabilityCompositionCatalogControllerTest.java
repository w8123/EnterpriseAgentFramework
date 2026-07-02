package com.enterprise.ai.capability.catalog.composition;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.enterprise.ai.agent.capability.catalog.tool.definition.ToolDefinitionEntity;
import com.enterprise.ai.agent.capability.catalog.tool.definition.ToolDefinitionParameter;
import com.enterprise.ai.capability.internal.CapabilityToolExecutionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CapabilityCompositionCatalogControllerTest {

    @Test
    void springContainerCanInstantiateCompositionControllerWithInjectedDependencies() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.registerBean(CapabilityCompositionCatalogService.class,
                    () -> mock(CapabilityCompositionCatalogService.class));
            context.registerBean(CapabilityToolExecutionService.class,
                    () -> mock(CapabilityToolExecutionService.class));
            context.registerBean(CapabilityCompositionMetricsService.class,
                    () -> mock(CapabilityCompositionMetricsService.class));
            context.registerBean(CapabilityCompositionInteractionService.class,
                    () -> mock(CapabilityCompositionInteractionService.class));
            context.registerBean(CapabilityCompositionInteractionResumeService.class,
                    () -> mock(CapabilityCompositionInteractionResumeService.class));
            context.registerBean(CapabilityRuntimeCompositionExecutionClient.class,
                    () -> mock(CapabilityRuntimeCompositionExecutionClient.class));
            context.registerBean(ObjectMapper.class, (Supplier<ObjectMapper>) ObjectMapper::new);
            context.registerBean(CapabilityCompositionCatalogController.class);

            assertDoesNotThrow(context::refresh);
        }
    }

    @Test
    void keepsCompositionManagementRouteShapeOnCapabilityService() throws Exception {
        RequestMapping controllerMapping = CapabilityCompositionCatalogController.class.getAnnotation(RequestMapping.class);
        Method list = CapabilityCompositionCatalogController.class.getDeclaredMethod(
                "list", int.class, int.class, String.class, Boolean.class, Boolean.class, Long.class);
        Method get = CapabilityCompositionCatalogController.class.getDeclaredMethod("get", String.class);
        Method create = CapabilityCompositionCatalogController.class.getDeclaredMethod(
                "create", CapabilityCompositionCatalogController.CompositionUpsertRequest.class);
        Method update = CapabilityCompositionCatalogController.class.getDeclaredMethod(
                "update", String.class, CapabilityCompositionCatalogController.CompositionUpsertRequest.class);
        Method delete = CapabilityCompositionCatalogController.class.getDeclaredMethod("delete", String.class);
        Method toggle = CapabilityCompositionCatalogController.class.getDeclaredMethod(
                "toggle", String.class, CapabilityCompositionCatalogController.CompositionToggleRequest.class);
        Method test = CapabilityCompositionCatalogController.class.getDeclaredMethod(
                "test", String.class, CapabilityCompositionCatalogController.CompositionTestRequest.class);
        Method testResume = CapabilityCompositionCatalogController.class.getDeclaredMethod(
                "testResume", String.class, CapabilityCompositionCatalogController.CompositionTestResumeRequest.class);
        Method metrics = CapabilityCompositionCatalogController.class.getDeclaredMethod("metrics", String.class, int.class);
        Method listPending = CapabilityCompositionCatalogController.class.getDeclaredMethod("listPendingForAdminTest");
        Method cancelPending = CapabilityCompositionCatalogController.class.getDeclaredMethod(
                "cancelPendingForAdminTest", String.class);
        Method cancelAllPending = CapabilityCompositionCatalogController.class.getDeclaredMethod(
                "cancelAllPendingForAdminTest");

        assertArrayEquals(new String[] {"/api/compositions", "/api/skills"}, controllerMapping.value());
        assertArrayEquals(new String[] {}, list.getAnnotation(GetMapping.class).value());
        assertArrayEquals(new String[] {"/{name}"}, get.getAnnotation(GetMapping.class).value());
        assertArrayEquals(new String[] {}, create.getAnnotation(PostMapping.class).value());
        assertArrayEquals(new String[] {"/{name}"}, update.getAnnotation(PutMapping.class).value());
        assertArrayEquals(new String[] {"/{name}"}, delete.getAnnotation(DeleteMapping.class).value());
        assertArrayEquals(new String[] {"/{name}/toggle"}, toggle.getAnnotation(PutMapping.class).value());
        assertArrayEquals(new String[] {"/{name}/test"}, test.getAnnotation(PostMapping.class).value());
        assertArrayEquals(new String[] {"/{name}/test/resume"}, testResume.getAnnotation(PostMapping.class).value());
        assertArrayEquals(new String[] {"/{name}/metrics"}, metrics.getAnnotation(GetMapping.class).value());
        assertArrayEquals(new String[] {"/pending-interactions/admin-test"}, listPending.getAnnotation(GetMapping.class).value());
        assertArrayEquals(new String[] {"/pending-interactions/admin-test/{interactionId}"}, cancelPending.getAnnotation(DeleteMapping.class).value());
        assertArrayEquals(new String[] {"/pending-interactions/admin-test/cancel-all"}, cancelAllPending.getAnnotation(PostMapping.class).value());
        assertNull(CapabilityCompositionCatalogController.class.getDeclaredMethod("toDto", ToolDefinitionEntity.class)
                .getAnnotation(PostMapping.class));
    }

    @Test
    void listReturnsCompositionPage() {
        CapabilityCompositionCatalogService service = mock(CapabilityCompositionCatalogService.class);
        CapabilityCompositionCatalogController controller = new CapabilityCompositionCatalogController(service);
        ToolDefinitionEntity entity = skill("order_composer");
        Page<ToolDefinitionEntity> page = new Page<>(1, 20, 1);
        page.setRecords(List.of(entity));
        when(service.page(1, 20, "order", true, false, 7L)).thenReturn(page);
        when(service.parseParameters(null)).thenReturn(List.of());
        when(service.parseSpecForDto(entity)).thenReturn(java.util.Map.of("systemPrompt", "Handle order operations"));

        ResponseEntity<CapabilityCompositionCatalogController.CompositionListPageResponse> response =
                controller.list(1, 20, "order", true, false, 7L);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(1, response.getBody().records().size());
        assertEquals("order_composer", response.getBody().records().get(0).name());
    }

    @Test
    void getReturnsNotFoundForMissingComposition() {
        CapabilityCompositionCatalogService service = mock(CapabilityCompositionCatalogService.class);
        CapabilityCompositionCatalogController controller = new CapabilityCompositionCatalogController(service);
        when(service.findSkillByName("missing")).thenReturn(java.util.Optional.empty());

        ResponseEntity<CapabilityCompositionCatalogController.CompositionInfoDTO> response = controller.get("missing");

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
    }

    @Test
    void createDelegatesToCapabilityCompositionService() throws Exception {
        CapabilityCompositionCatalogService service = mock(CapabilityCompositionCatalogService.class);
        CapabilityCompositionCatalogController controller = new CapabilityCompositionCatalogController(service);
        CapabilityCompositionCatalogController.CompositionUpsertRequest request =
                request("order_composer", false);
        ToolDefinitionEntity created = skill("order_composer");
        when(service.create(any())).thenReturn(created);
        when(service.parseParameters(null)).thenReturn(List.of());
        when(service.parseSpecForDto(created)).thenReturn(java.util.Map.of("systemPrompt", "Handle order operations"));

        ResponseEntity<?> response = controller.create(request);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        CapabilityCompositionCatalogController.CompositionInfoDTO body =
                (CapabilityCompositionCatalogController.CompositionInfoDTO) response.getBody();
        assertEquals("order_composer", body.name());
        verify(service).create(any());
    }

    @Test
    void toggleDelegatesToCapabilityCompositionService() {
        CapabilityCompositionCatalogService service = mock(CapabilityCompositionCatalogService.class);
        CapabilityCompositionCatalogController controller = new CapabilityCompositionCatalogController(service);
        ToolDefinitionEntity toggled = skill("order_composer");
        toggled.setEnabled(false);
        when(service.toggle("order_composer", false)).thenReturn(toggled);
        when(service.parseParameters(null)).thenReturn(List.of());
        when(service.parseSpecForDto(toggled)).thenReturn(java.util.Map.of("systemPrompt", "Handle order operations"));

        ResponseEntity<?> response = controller.toggle(
                "order_composer",
                new CapabilityCompositionCatalogController.CompositionToggleRequest(false));

        assertEquals(HttpStatus.OK, response.getStatusCode());
        CapabilityCompositionCatalogController.CompositionInfoDTO body =
                (CapabilityCompositionCatalogController.CompositionInfoDTO) response.getBody();
        assertEquals(false, body.enabled());
        verify(service).toggle("order_composer", false);
    }

    @Test
    void testInteractiveFormCompositionDelegatesToTargetTool() {
        CapabilityCompositionCatalogService service = mock(CapabilityCompositionCatalogService.class);
        CapabilityToolExecutionService executionService = mock(CapabilityToolExecutionService.class);
        CapabilityCompositionCatalogController controller = new CapabilityCompositionCatalogController(
                service, new ObjectMapper(), executionService);
        ToolDefinitionEntity composition = interactiveForm("order_form");
        when(service.findSkillByName("order_form")).thenReturn(java.util.Optional.of(composition));
        when(executionService.execute("orders_create", Map.of("input", Map.of("orderId", "A-1"))))
                .thenReturn(Map.of("success", true, "data", Map.of("status", "ok")));

        ResponseEntity<CapabilityCompositionCatalogController.CompositionTestResult> response = controller.test(
                "order_form",
                new CapabilityCompositionCatalogController.CompositionTestRequest(Map.of("orderId", "A-1")));

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(true, response.getBody().success());
        assertEquals("{status=ok}", response.getBody().result());
        assertEquals(false, response.getBody().interactionPending());
        verify(executionService).execute("orders_create", Map.of("input", Map.of("orderId", "A-1")));
    }

    @Test
    void testSubAgentCompositionDelegatesToRuntimeCompositionExecution() {
        CapabilityCompositionCatalogService service = mock(CapabilityCompositionCatalogService.class);
        CapabilityRuntimeCompositionExecutionClient runtimeClient =
                mock(CapabilityRuntimeCompositionExecutionClient.class);
        CapabilityCompositionCatalogController controller = new CapabilityCompositionCatalogController(
                service, new ObjectMapper(), null, null, null, null, runtimeClient);
        ToolDefinitionEntity composition = skill("order_composer");
        composition.setQualifiedName("orders:orderComposer");
        when(service.findSkillByName("order_composer")).thenReturn(java.util.Optional.of(composition));
        when(runtimeClient.executeComposition(
                "orders:orderComposer",
                Map.of("input", Map.of("orderId", "A-1"), "params", Map.of("orderId", "A-1"))))
                .thenReturn(Map.of("success", true, "answer", "created"));

        ResponseEntity<CapabilityCompositionCatalogController.CompositionTestResult> response = controller.test(
                "order_composer",
                new CapabilityCompositionCatalogController.CompositionTestRequest(Map.of("orderId", "A-1")));

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(true, response.getBody().success());
        assertEquals("created", response.getBody().result());
        assertEquals(false, response.getBody().interactionPending());
        assertNull(response.getBody().errorMessage());
        verify(runtimeClient).executeComposition(
                "orders:orderComposer",
                Map.of("input", Map.of("orderId", "A-1"), "params", Map.of("orderId", "A-1")));
    }

    @Test
    void testResumeDelegatesToCapabilityInteractionResumeService() {
        CapabilityCompositionCatalogService service = mock(CapabilityCompositionCatalogService.class);
        CapabilityCompositionInteractionResumeService resumeService =
                mock(CapabilityCompositionInteractionResumeService.class);
        CapabilityCompositionCatalogController controller = new CapabilityCompositionCatalogController(
                service, new ObjectMapper(), null, null, null, resumeService);
        ToolDefinitionEntity composition = interactiveForm("order_form");
        CapabilityCompositionInteractionResumeService.ResumeResult result =
                new CapabilityCompositionInteractionResumeService.ResumeResult(
                        true, "{status=ok}", null, false, null, null);
        when(service.findSkillByName("order_form")).thenReturn(java.util.Optional.of(composition));
        when(resumeService.resumeAdminTest("order_form", "ix-1", "submit", Map.of("orderId", "A-1")))
                .thenReturn(result);

        ResponseEntity<CapabilityCompositionCatalogController.CompositionTestResult> response = controller.testResume(
                "order_form",
                new CapabilityCompositionCatalogController.CompositionTestResumeRequest(
                        "ix-1", "submit", Map.of("orderId", "A-1")));

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(true, response.getBody().success());
        assertEquals("{status=ok}", response.getBody().result());
        verify(resumeService).resumeAdminTest("order_form", "ix-1", "submit", Map.of("orderId", "A-1"));
    }

    @Test
    void getsCompositionMetricsFromCapabilityMetricsService() {
        CapabilityCompositionCatalogService service = mock(CapabilityCompositionCatalogService.class);
        CapabilityCompositionMetricsService metricsService = mock(CapabilityCompositionMetricsService.class);
        CapabilityCompositionCatalogController controller = new CapabilityCompositionCatalogController(
                service, new ObjectMapper(), null, metricsService, null);
        ToolDefinitionEntity composition = skill("order_composer");
        CapabilityCompositionMetricsService.CompositionMetricsView view =
                new CapabilityCompositionMetricsService.CompositionMetricsView(
                        12, 30, 8, 15, 3, 0.666D,
                        List.of(new CapabilityCompositionMetricsService.CompositionMetricPoint(
                                "2026-06-30", 3, 0.666D, 30, 15)));
        when(service.findSkillByName("order_composer")).thenReturn(java.util.Optional.of(composition));
        when(metricsService.metrics("order_composer", 7)).thenReturn(view);

        ResponseEntity<?> response = controller.metrics("order_composer", 7);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(view, response.getBody());
        verify(metricsService).metrics("order_composer", 7);
    }

    @Test
    void listsAndCancelsAdminTestPendingInteractions() {
        CapabilityCompositionCatalogService service = mock(CapabilityCompositionCatalogService.class);
        CapabilityCompositionInteractionService interactionService = mock(CapabilityCompositionInteractionService.class);
        CapabilityCompositionCatalogController controller = new CapabilityCompositionCatalogController(
                service, new ObjectMapper(), null, null, interactionService);
        CapabilityCompositionInteractionService.PendingAdminTestInteractionDTO pending =
                new CapabilityCompositionInteractionService.PendingAdminTestInteractionDTO(
                        "ix-1", "order_form", "PENDING", null, null, null, "Confirm order");
        when(interactionService.listPendingForAdminTest()).thenReturn(List.of(pending));
        when(interactionService.cancelPendingForAdminTest("ix-1"))
                .thenReturn(CapabilityCompositionInteractionService.CancelResult.cancelled());
        when(interactionService.cancelAllPendingForAdminTest()).thenReturn(2);

        ResponseEntity<List<CapabilityCompositionInteractionService.PendingAdminTestInteractionDTO>> list =
                controller.listPendingForAdminTest();
        ResponseEntity<?> cancelOne = controller.cancelPendingForAdminTest("ix-1");
        ResponseEntity<Map<String, Integer>> cancelAll = controller.cancelAllPendingForAdminTest();

        assertEquals(List.of(pending), list.getBody());
        assertEquals(HttpStatus.NO_CONTENT, cancelOne.getStatusCode());
        assertEquals(2, cancelAll.getBody().get("cancelled"));
        verify(interactionService).cancelPendingForAdminTest("ix-1");
        verify(interactionService).cancelAllPendingForAdminTest();
    }

    private ToolDefinitionEntity skill(String name) {
        ToolDefinitionEntity entity = new ToolDefinitionEntity();
        entity.setId(12L);
        entity.setName(name);
        entity.setKind("SKILL");
        entity.setDescription("Order composition");
        entity.setParametersJson(null);
        entity.setSource("manual");
        entity.setSkillKind("SUB_AGENT");
        entity.setSideEffect("WRITE");
        entity.setEnabled(true);
        entity.setAgentVisible(true);
        entity.setDraft(false);
        entity.setSpecJson("{\"systemPrompt\":\"Handle order operations\",\"toolWhitelist\":[\"orders_create\"]}");
        return entity;
    }

    private ToolDefinitionEntity interactiveForm(String name) {
        ToolDefinitionEntity entity = skill(name);
        entity.setSkillKind("INTERACTIVE_FORM");
        entity.setSpecJson("{\"targetTool\":\"orders_create\",\"fields\":[{\"key\":\"orderId\",\"label\":\"Order\",\"source\":{\"kind\":\"manual\"}}]}");
        return entity;
    }

    private CapabilityCompositionCatalogController.CompositionUpsertRequest request(String name, boolean draft)
            throws Exception {
        return new CapabilityCompositionCatalogController.CompositionUpsertRequest(
                name,
                "Order composition",
                List.of(new ToolDefinitionParameter("orderId", "string", "Order id", true, "body")),
                "SUB_AGENT",
                "WRITE",
                7L,
                "orders",
                "PROJECT",
                "orders:orderComposer",
                true,
                true,
                new com.fasterxml.jackson.databind.ObjectMapper().readTree(
                        "{\"systemPrompt\":\"Handle order operations\",\"toolWhitelist\":[\"orders_create\"]}"),
                draft
        );
    }
}
