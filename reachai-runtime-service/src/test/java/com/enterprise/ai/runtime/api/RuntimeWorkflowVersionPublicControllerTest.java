package com.enterprise.ai.runtime.api;

import com.enterprise.ai.runtime.workflow.RuntimeWorkflowReleaseValidationResult;
import com.enterprise.ai.runtime.workflow.RuntimeWorkflowRevisionConflictException;
import com.enterprise.ai.runtime.workflow.RuntimeWorkflowVersionEntity;
import com.enterprise.ai.runtime.workflow.RuntimeWorkflowVersionService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
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

class RuntimeWorkflowVersionPublicControllerTest {

    @Test
    void exposesOnlyCanonicalVersionRoutes() throws Exception {
        Method list = RuntimeWorkflowVersionPublicController.class.getDeclaredMethod("list", String.class);
        Method publish = RuntimeWorkflowVersionPublicController.class.getDeclaredMethod(
                "publishExplicit", String.class, RuntimeWorkflowVersionPublishRequest.class);
        Method validate = RuntimeWorkflowVersionPublicController.class.getDeclaredMethod("validate", String.class);
        Method rollback = RuntimeWorkflowVersionPublicController.class.getDeclaredMethod(
                "rollback", String.class, Long.class, RuntimeWorkflowVersionRollbackRequest.class);

        assertArrayEquals(new String[]{"/api/workflows/{workflowId}/versions"},
                list.getAnnotation(GetMapping.class).value());
        assertArrayEquals(new String[]{"/api/workflows/{workflowId}/versions/publish"},
                publish.getAnnotation(PostMapping.class).value());
        assertArrayEquals(new String[]{"/api/workflows/{workflowId}/versions/validate"},
                validate.getAnnotation(PostMapping.class).value());
        assertArrayEquals(new String[]{"/api/workflows/{workflowId}/versions/{versionId}/rollback"},
                rollback.getAnnotation(PostMapping.class).value());
    }

    @Test
    void delegatesVersionOperations() {
        RuntimeWorkflowVersionService service = mock(RuntimeWorkflowVersionService.class);
        RuntimeWorkflowVersionPublicController controller = new RuntimeWorkflowVersionPublicController(service);
        RuntimeWorkflowVersionEntity version = version();
        RuntimeWorkflowVersionPublishRequest publishRequest = new RuntimeWorkflowVersionPublishRequest(
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
    void mapsValidationErrorsAndPropagatesRevisionConflicts() {
        RuntimeWorkflowVersionService service = mock(RuntimeWorkflowVersionService.class);
        RuntimeWorkflowVersionPublicController controller = new RuntimeWorkflowVersionPublicController(service);
        when(service.publish("wf-1", "bad", 100, null, null, null))
                .thenThrow(new IllegalArgumentException("workflow release validation failed"));
        assertEquals(HttpStatus.BAD_REQUEST, controller.publishExplicit(
                "wf-1", new RuntimeWorkflowVersionPublishRequest("bad", 100, null, null)).getStatusCode());

        RuntimeWorkflowRevisionConflictException conflict = new RuntimeWorkflowRevisionConflictException(
                "wf-1", "2026-07-14T10:30:00", "2026-07-14T10:31:00");
        when(service.publish("wf-1", "v1.0.0", 100, null, null, "2026-07-14T10:30:00"))
                .thenThrow(conflict);
        assertEquals(conflict, assertThrows(RuntimeWorkflowRevisionConflictException.class,
                () -> controller.publishExplicit("wf-1", new RuntimeWorkflowVersionPublishRequest(
                        "v1.0.0", 100, null, null, "2026-07-14T10:30:00"))));
    }

    private RuntimeWorkflowVersionEntity version() {
        RuntimeWorkflowVersionEntity entity = new RuntimeWorkflowVersionEntity();
        entity.setId(1L);
        entity.setWorkflowId("wf-1");
        entity.setVersion("v1.0.0");
        entity.setStatus("ACTIVE");
        return entity;
    }
}
