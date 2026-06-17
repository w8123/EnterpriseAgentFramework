package com.enterprise.ai.agent.controller;

import com.enterprise.ai.agent.platform.auth.PlatformAuthContext;
import com.enterprise.ai.agent.platform.auth.PlatformPrincipal;
import com.enterprise.ai.agent.workflow.aicoding.WorkflowAccessDeniedException;
import com.enterprise.ai.agent.workflow.aicoding.WorkflowAiCodingUnauthorizedException;
import com.enterprise.ai.agent.workflow.aicoding.WorkflowAiCodingContextResponse;
import com.enterprise.ai.agent.workflow.aicoding.WorkflowAiCodingCreateRequest;
import com.enterprise.ai.agent.workflow.aicoding.WorkflowAiCodingService;
import com.enterprise.ai.agent.workflow.aicoding.pageassistant.WorkflowPageAssistantAiCodingService;
import com.enterprise.ai.agent.workflow.aicoding.pageassistant.WorkflowPageAssistantCatalogResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import java.lang.reflect.Method;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WorkflowAiCodingControllerTest {

    private final WorkflowAiCodingControllerAdvice advice = new WorkflowAiCodingControllerAdvice();

    @AfterEach
    void clearAuth() {
        PlatformAuthContext.clear();
    }

    @Test
    void exposesWorkflowAiCodingEndpoints() throws Exception {
        RequestMapping root = WorkflowAiCodingController.class.getAnnotation(RequestMapping.class);
        assertArrayEquals(new String[]{"/api/workflows/{workflowId}/ai-coding"}, root.value());

        Method context = WorkflowAiCodingController.class.getMethod("context", String.class);
        assertArrayEquals(new String[]{"/context"}, context.getAnnotation(GetMapping.class).value());
    }

    @Test
    void contextDelegatesToService() {
        WorkflowAiCodingService service = mock(WorkflowAiCodingService.class);
        WorkflowAiCodingController controller = new WorkflowAiCodingController(service, mock(WorkflowPageAssistantAiCodingService.class));
        WorkflowAiCodingContextResponse expected = WorkflowAiCodingContextResponse.builder().build();
        when(service.getContext("wf-1")).thenReturn(expected);

        assertEquals(expected, controller.context("wf-1").getBody());
    }

    @Test
    void catalogControllerExposesCreateEndpoint() throws Exception {
        RequestMapping root = WorkflowAiCodingCatalogController.class.getAnnotation(RequestMapping.class);
        assertArrayEquals(new String[]{"/api/workflows/ai-coding"}, root.value());

        Method create = WorkflowAiCodingCatalogController.class.getMethod("create", WorkflowAiCodingCreateRequest.class);
        assertArrayEquals(new String[]{"/workflows"}, create.getAnnotation(PostMapping.class).value());
    }

    @Test
    void catalogCreateDelegatesToService() {
        WorkflowAiCodingService service = mock(WorkflowAiCodingService.class);
        WorkflowAiCodingCatalogController controller = new WorkflowAiCodingCatalogController(service);
        WorkflowAiCodingCreateRequest request = WorkflowAiCodingCreateRequest.builder()
                .name("Demo")
                .keySlug("demo-flow")
                .projectId(7L)
                .projectCode("orders")
                .build();
        WorkflowAiCodingContextResponse expected = WorkflowAiCodingContextResponse.builder().build();
        when(service.create(request)).thenReturn(expected);

        assertEquals(expected, controller.create(request).getBody());
    }

    @Test
    void adviceMapsNotFoundTo404() {
        var response = advice.badRequest(new IllegalArgumentException("workflow not found: wf-404"));
        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
    }

    @Test
    void pageAssistantCatalogDelegatesToService() {
        WorkflowPageAssistantAiCodingService pageAssistantService = mock(WorkflowPageAssistantAiCodingService.class);
        WorkflowAiCodingController controller = new WorkflowAiCodingController(
                mock(WorkflowAiCodingService.class), pageAssistantService);
        WorkflowPageAssistantCatalogResponse expected = WorkflowPageAssistantCatalogResponse.builder().build();
        when(pageAssistantService.getCatalog("wf-page")).thenReturn(expected);

        assertEquals(expected, controller.pageAssistantCatalog("wf-page").getBody());
    }

    @Test
    void adviceMapsUnauthorizedTo401() {
        var response = advice.unauthorized(new WorkflowAiCodingUnauthorizedException("aiCodingKey is required"));
        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
    }

    @Test
    void adviceMapsAccessDeniedTo403() {
        var response = advice.forbidden(new WorkflowAccessDeniedException("invalid AI Coding access key for workflow project"));
        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
    }
}
