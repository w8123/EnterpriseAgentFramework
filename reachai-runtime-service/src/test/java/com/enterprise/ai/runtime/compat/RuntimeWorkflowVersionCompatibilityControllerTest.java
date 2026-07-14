package com.enterprise.ai.runtime.compat;

import com.enterprise.ai.runtime.workflow.RuntimeWorkflowReleaseValidationResult;
import com.enterprise.ai.runtime.workflow.RuntimeWorkflowRevisionConflictException;
import com.enterprise.ai.runtime.workflow.RuntimeWorkflowVersionEntity;
import com.enterprise.ai.runtime.workflow.RuntimeWorkflowVersionService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RuntimeWorkflowVersionCompatibilityControllerTest {

    @Test
    void keepsPublicWorkflowVersionRoutesOnRuntimeService() throws Exception {
        Method list = RuntimeWorkflowVersionCompatibilityController.class.getDeclaredMethod("list", String.class);
        Method publish = RuntimeWorkflowVersionCompatibilityController.class
                .getDeclaredMethod("publish", String.class, RuntimeWorkflowVersionPublishRequest.class);
        Method publishExplicit = RuntimeWorkflowVersionCompatibilityController.class
                .getDeclaredMethod("publishExplicit", String.class, RuntimeWorkflowVersionPublishRequest.class);
        Method validate = RuntimeWorkflowVersionCompatibilityController.class.getDeclaredMethod("validate", String.class);
        Method rollback = RuntimeWorkflowVersionCompatibilityController.class
                .getDeclaredMethod("rollback", String.class, Long.class, RuntimeWorkflowVersionRollbackRequest.class);

        assertArrayEquals(new String[] {"/api/workflows/{workflowId}/versions"}, list.getAnnotation(GetMapping.class).value());
        assertArrayEquals(new String[] {"/api/workflows/{workflowId}/versions"}, publish.getAnnotation(PostMapping.class).value());
        assertArrayEquals(new String[] {"/api/workflows/{workflowId}/versions/publish"},
                publishExplicit.getAnnotation(PostMapping.class).value());
        assertArrayEquals(new String[] {"/api/workflows/{workflowId}/versions/validate"},
                validate.getAnnotation(PostMapping.class).value());
        assertArrayEquals(new String[] {"/api/workflows/{workflowId}/versions/{versionId}/rollback"},
                rollback.getAnnotation(PostMapping.class).value());
    }

    @Test
    void delegatesVersionOperationsToRuntimeService() {
        RuntimeWorkflowVersionService service = mock(RuntimeWorkflowVersionService.class);
        RuntimeWorkflowVersionCompatibilityController controller =
                new RuntimeWorkflowVersionCompatibilityController(service);
        RuntimeWorkflowVersionEntity version = version(1L);
        RuntimeWorkflowVersionPublishRequest publishRequest =
                new RuntimeWorkflowVersionPublishRequest(
                        "v1.0.0", 100, "first", "alice", "2026-07-14T10:30:00");
        RuntimeWorkflowVersionRollbackRequest rollbackRequest = new RuntimeWorkflowVersionRollbackRequest("bob");
        RuntimeWorkflowReleaseValidationResult validation = RuntimeWorkflowReleaseValidationResult.builder().build();
        when(service.listVersions("wf-1")).thenReturn(List.of(version));
        when(service.publish("wf-1", "v1.0.0", 100, "first", "alice", "2026-07-14T10:30:00"))
                .thenReturn(version);
        when(service.validateRelease("wf-1")).thenReturn(validation);
        when(service.rollback("wf-1", 1L, "bob")).thenReturn(version);

        assertEquals(List.of(version), controller.list("wf-1").getBody());
        assertEquals(version, controller.publishExplicit("wf-1", publishRequest).getBody());
        assertEquals(validation, controller.validate("wf-1").getBody());
        assertEquals(version, controller.rollback("wf-1", 1L, rollbackRequest).getBody());
        verify(service).listVersions("wf-1");
        verify(service).publish("wf-1", "v1.0.0", 100, "first", "alice", "2026-07-14T10:30:00");
        verify(service).validateRelease("wf-1");
        verify(service).rollback("wf-1", 1L, "bob");
    }

    @Test
    void mapsVersionValidationErrorsToBadRequest() {
        RuntimeWorkflowVersionService service = mock(RuntimeWorkflowVersionService.class);
        RuntimeWorkflowVersionCompatibilityController controller =
                new RuntimeWorkflowVersionCompatibilityController(service);
        when(service.publish("wf-1", "bad", 100, null, null, null))
                .thenThrow(new IllegalArgumentException("workflow release validation failed: GRAPH_ENTRY_MISSING"));

        ResponseEntity<?> response = controller.publishExplicit("wf-1",
                new RuntimeWorkflowVersionPublishRequest("bad", 100, null, null));

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
    }

    @Test
    void letsRevisionConflictsPropagateAsConflictResponses() {
        RuntimeWorkflowVersionService service = mock(RuntimeWorkflowVersionService.class);
        RuntimeWorkflowVersionCompatibilityController controller =
                new RuntimeWorkflowVersionCompatibilityController(service);
        String baseRevision = "2026-07-14T10:30:00";
        RuntimeWorkflowRevisionConflictException conflict = new RuntimeWorkflowRevisionConflictException(
                "wf-1", baseRevision, "2026-07-14T10:31:00");
        when(service.publish("wf-1", "v1.0.0", 100, null, null, baseRevision))
                .thenThrow(conflict);

        RuntimeWorkflowRevisionConflictException thrown = assertThrows(
                RuntimeWorkflowRevisionConflictException.class,
                () -> controller.publishExplicit("wf-1",
                        new RuntimeWorkflowVersionPublishRequest("v1.0.0", 100, null, null, baseRevision)));

        assertEquals(conflict, thrown);
    }

    private RuntimeWorkflowVersionEntity version(Long id) {
        RuntimeWorkflowVersionEntity entity = new RuntimeWorkflowVersionEntity();
        entity.setId(id);
        entity.setWorkflowId("wf-1");
        entity.setVersion("v1.0.0");
        entity.setStatus("ACTIVE");
        return entity;
    }
}
