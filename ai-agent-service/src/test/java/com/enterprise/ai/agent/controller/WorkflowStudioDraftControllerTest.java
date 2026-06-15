package com.enterprise.ai.agent.controller;

import com.enterprise.ai.agent.graph.GraphSpec;
import com.enterprise.ai.agent.studio.WorkflowDraftEditRequest;
import com.enterprise.ai.agent.studio.WorkflowDraftEditResult;
import com.enterprise.ai.agent.studio.WorkflowDraftEditService;
import com.enterprise.ai.agent.studio.WorkflowDraftGenerationRequest;
import com.enterprise.ai.agent.studio.WorkflowDraftGenerationResult;
import com.enterprise.ai.agent.studio.WorkflowDraftGenerationService;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WorkflowStudioDraftControllerTest {

    @Test
    void exposesWorkflowStudioDraftEndpoints() throws Exception {
        RequestMapping root = WorkflowStudioDraftController.class.getAnnotation(RequestMapping.class);
        assertArrayEquals(new String[]{"/api/workflows/studio"}, root.value());

        Method generate = WorkflowStudioDraftController.class.getMethod(
                "generateDraft",
                WorkflowDraftGenerationRequest.class);
        assertArrayEquals(new String[]{"/generate-draft"}, generate.getAnnotation(PostMapping.class).value());

        Method edit = WorkflowStudioDraftController.class.getMethod(
                "editDraft",
                WorkflowDraftEditRequest.class);
        assertArrayEquals(new String[]{"/edit-draft"}, edit.getAnnotation(PostMapping.class).value());
    }

    @Test
    void generateDraftDelegatesToWorkflowDraftGenerationService() {
        WorkflowDraftGenerationService generationService = mock(WorkflowDraftGenerationService.class);
        WorkflowDraftEditService editService = mock(WorkflowDraftEditService.class);
        WorkflowStudioDraftController controller = new WorkflowStudioDraftController(generationService, editService);
        WorkflowDraftGenerationRequest request = WorkflowDraftGenerationRequest.builder()
                .agentId("workflow-1")
                .agentName("Orders Workflow")
                .requirement("build order search")
                .projectCode("orders")
                .build();
        WorkflowDraftGenerationResult result = new WorkflowDraftGenerationResult(
                "stub",
                Map.of("nodes", List.of()),
                GraphSpec.builder().code("orders").build(),
                List.of(),
                List.of(),
                List.of());
        when(generationService.generate(request)).thenReturn(result);

        assertEquals(result, controller.generateDraft(request).getBody());
        verify(generationService).generate(request);
    }

    @Test
    void editDraftDelegatesToWorkflowDraftEditService() {
        WorkflowDraftGenerationService generationService = mock(WorkflowDraftGenerationService.class);
        WorkflowDraftEditService editService = mock(WorkflowDraftEditService.class);
        WorkflowStudioDraftController controller = new WorkflowStudioDraftController(generationService, editService);
        WorkflowDraftEditRequest request = WorkflowDraftEditRequest.builder()
                .agentId("workflow-1")
                .agentName("Orders Workflow")
                .instruction("add approval step")
                .projectCode("orders")
                .build();
        WorkflowDraftEditResult result = new WorkflowDraftEditResult(
                "stub",
                "added approval",
                List.of(),
                Map.of("nodes", List.of()),
                GraphSpec.builder().code("orders").build(),
                List.of(),
                List.of(),
                List.of());
        when(editService.edit(request)).thenReturn(result);

        assertEquals(result, controller.editDraft(request).getBody());
        verify(editService).edit(request);
    }
}
