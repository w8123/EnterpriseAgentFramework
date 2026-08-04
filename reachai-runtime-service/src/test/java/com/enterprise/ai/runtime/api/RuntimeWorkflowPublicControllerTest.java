package com.enterprise.ai.runtime.api;

import com.enterprise.ai.runtime.workflow.RuntimeWorkflowDebugService;
import com.enterprise.ai.runtime.workflow.RuntimeWorkflowDefinitionEntity;
import com.enterprise.ai.runtime.workflow.RuntimeWorkflowDefinitionService;
import com.enterprise.ai.runtime.workflow.RuntimeWorkflowReleaseValidationService;
import com.enterprise.ai.runtime.workflow.RuntimeWorkflowSearchPage;
import com.enterprise.ai.runtime.workflow.RuntimeWorkflowStudioService;
import com.enterprise.ai.runtime.workflow.proposal.RuntimeWorkflowProposalEditRequest;
import com.enterprise.ai.runtime.workflow.proposal.RuntimeWorkflowProposalEditService;
import com.enterprise.ai.runtime.workflow.proposal.RuntimeWorkflowProposalGenerationRequest;
import com.enterprise.ai.runtime.workflow.proposal.RuntimeWorkflowProposalGenerationService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RuntimeWorkflowPublicControllerTest {

    @Test
    void exposesOnlyCanonicalWorkflowRoutes() throws Exception {
        Method list = RuntimeWorkflowPublicController.class.getDeclaredMethod(
                "list", Long.class, String.class, String.class, String.class, String.class);
        Method search = RuntimeWorkflowPublicController.class.getDeclaredMethod(
                "search", Long.class, String.class, String.class, String.class, String.class,
                String.class, int.class, int.class);
        Method create = RuntimeWorkflowPublicController.class.getDeclaredMethod(
                "create", RuntimeWorkflowDefinitionEntity.class);
        Method get = RuntimeWorkflowPublicController.class.getDeclaredMethod("get", String.class);
        Method update = RuntimeWorkflowPublicController.class.getDeclaredMethod(
                "update", String.class, RuntimeWorkflowDefinitionEntity.class);
        Method delete = RuntimeWorkflowPublicController.class.getDeclaredMethod("delete", String.class);
        Method workingCopy = RuntimeWorkflowPublicController.class.getDeclaredMethod("workingCopy", String.class);
        Method saveWorkingCopy = RuntimeWorkflowPublicController.class.getDeclaredMethod(
                "saveWorkingCopy", String.class, RuntimeWorkflowWorkingCopySaveRequest.class);
        Method generateProposal = RuntimeWorkflowPublicController.class.getDeclaredMethod(
                "generateWorkflowProposal", RuntimeWorkflowProposalGenerationRequest.class);
        Method editProposal = RuntimeWorkflowPublicController.class.getDeclaredMethod(
                "editWorkflowProposal", RuntimeWorkflowProposalEditRequest.class);

        assertArrayEquals(new String[]{"/api/workflows"}, list.getAnnotation(GetMapping.class).value());
        assertArrayEquals(new String[]{"/api/workflows/search"}, search.getAnnotation(GetMapping.class).value());
        assertArrayEquals(new String[]{"/api/workflows"}, create.getAnnotation(PostMapping.class).value());
        assertArrayEquals(new String[]{"/api/workflows/{id}"}, get.getAnnotation(GetMapping.class).value());
        assertArrayEquals(new String[]{"/api/workflows/{id}"}, update.getAnnotation(PutMapping.class).value());
        assertArrayEquals(new String[]{"/api/workflows/{id}"}, delete.getAnnotation(DeleteMapping.class).value());
        assertArrayEquals(new String[]{"/api/workflows/{id}/working-copy"},
                workingCopy.getAnnotation(GetMapping.class).value());
        assertArrayEquals(new String[]{"/api/workflows/{id}/working-copy"},
                saveWorkingCopy.getAnnotation(PutMapping.class).value());
        assertArrayEquals(new String[]{"/api/workflows/studio/proposals/generate"},
                generateProposal.getAnnotation(PostMapping.class).value());
        assertArrayEquals(new String[]{"/api/workflows/studio/proposals/edit"},
                editProposal.getAnnotation(PostMapping.class).value());

        List<String> methodNames = Arrays.stream(RuntimeWorkflowPublicController.class.getDeclaredMethods())
                .map(Method::getName)
                .toList();
        assertFalse(methodNames.contains("studio"));
        assertFalse(methodNames.contains("saveStudio"));
        assertFalse(methodNames.contains("generateWorkflowStudioDraft"));
        assertFalse(methodNames.contains("editWorkflowStudioDraft"));
    }

    @Test
    void delegatesCanonicalListAndSearchFilters() {
        RuntimeWorkflowDefinitionService service = mock(RuntimeWorkflowDefinitionService.class);
        RuntimeWorkflowPublicController controller = controller(service);
        List<RuntimeWorkflowDefinitionEntity> workflows = List.of(workflow("wf-1"));
        RuntimeWorkflowSearchPage page = new RuntimeWorkflowSearchPage(workflows, 1L, 1, 10);
        when(service.list(7L, "orders", "GENERAL", "USER", "DRAFT")).thenReturn(workflows);
        when(service.search(7L, "orders", "GENERAL", "USER", "DRAFT", "order", 1, 10))
                .thenReturn(page);

        assertEquals(workflows, controller.list(7L, "orders", "GENERAL", "USER", "DRAFT").getBody());
        assertEquals(page, controller.search(
                7L, "orders", "GENERAL", "USER", "DRAFT", "order", 1, 10).getBody());
        verify(service).list(7L, "orders", "GENERAL", "USER", "DRAFT");
        verify(service).search(7L, "orders", "GENERAL", "USER", "DRAFT", "order", 1, 10);
    }

    @Test
    void delegatesCanonicalWorkingCopySave() {
        RuntimeWorkflowStudioService studioService = mock(RuntimeWorkflowStudioService.class);
        RuntimeWorkflowPublicController controller = new RuntimeWorkflowPublicController(
                mock(RuntimeWorkflowDefinitionService.class),
                mock(RuntimeWorkflowReleaseValidationService.class),
                studioService,
                mock(RuntimeWorkflowDebugService.class),
                mock(RuntimeWorkflowProposalGenerationService.class),
                mock(RuntimeWorkflowProposalEditService.class));
        RuntimeWorkflowWorkingCopySaveRequest request = new RuntimeWorkflowWorkingCopySaveRequest(
                "{\"schemaVersion\":2,\"nodes\":[],\"edges\":[],\"exitNodeIds\":[]}", "{}", null);
        RuntimeWorkflowStudioService.WorkflowWorkingCopyState state = workingCopyState();
        when(studioService.saveWorkingCopy(org.mockito.ArgumentMatchers.eq("wf-1"),
                org.mockito.ArgumentMatchers.any())).thenReturn(state);

        assertEquals(state, controller.saveWorkingCopy("wf-1", request).getBody());
        verify(studioService).saveWorkingCopy(org.mockito.ArgumentMatchers.eq("wf-1"),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void returnsNotFoundForMissingWorkflow() {
        RuntimeWorkflowDefinitionService service = mock(RuntimeWorkflowDefinitionService.class);
        when(service.findById("missing")).thenReturn(java.util.Optional.empty());

        assertEquals(HttpStatus.NOT_FOUND, controller(service).get("missing").getStatusCode());
    }

    private RuntimeWorkflowPublicController controller(RuntimeWorkflowDefinitionService service) {
        return new RuntimeWorkflowPublicController(
                service,
                mock(RuntimeWorkflowReleaseValidationService.class),
                mock(RuntimeWorkflowStudioService.class),
                mock(RuntimeWorkflowDebugService.class),
                mock(RuntimeWorkflowProposalGenerationService.class),
                mock(RuntimeWorkflowProposalEditService.class));
    }

    private RuntimeWorkflowDefinitionEntity workflow(String id) {
        RuntimeWorkflowDefinitionEntity entity = new RuntimeWorkflowDefinitionEntity();
        entity.setId(id);
        entity.setKeySlug("orders");
        entity.setName("Orders");
        entity.setStatus("DRAFT");
        return entity;
    }

    private RuntimeWorkflowStudioService.WorkflowWorkingCopyState workingCopyState() {
        return new RuntimeWorkflowStudioService.WorkflowWorkingCopyState(
                "wf-1", 7L, "demo", "orders", "Orders", "desc",
                "{\"schemaVersion\":2,\"nodes\":[],\"edges\":[],\"exitNodeIds\":[]}", "{}",
                "GENERAL", "GRAPH_SPEC", "USER", "STUDIO", "llm-1", null, "DRAFT", null,
                "wf-1", null, null, null, null, true, null, null, true);
    }
}
