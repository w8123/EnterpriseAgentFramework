package com.enterprise.ai.control.runops;

import com.enterprise.ai.control.runops.TraceWorkflowCandidateApplicationService.CandidateTaskView;
import com.enterprise.ai.control.runops.TraceWorkflowCandidateApplicationService.CreateCandidateTaskRequest;
import com.enterprise.ai.control.runops.TraceWorkflowCandidateEligibility.EligibilityView;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TraceWorkflowCandidateControllerTest {

    @Test
    void exposesEligibilityAndTaskCreationRoutes() throws Exception {
        RequestMapping mapping = TraceWorkflowCandidateController.class
                .getAnnotation(RequestMapping.class);
        Method eligibility = TraceWorkflowCandidateController.class
                .getDeclaredMethod("eligibility", jakarta.servlet.http.HttpServletRequest.class, String.class);
        Method create = TraceWorkflowCandidateController.class
                .getDeclaredMethod("createTask", jakarta.servlet.http.HttpServletRequest.class, String.class,
                        CreateCandidateTaskRequest.class);

        assertArrayEquals(
                new String[]{"/api/runops/traces/{traceId}/workflow-candidate"},
                mapping.value());
        assertArrayEquals(new String[]{"/eligibility"},
                eligibility.getAnnotation(GetMapping.class).value());
        assertArrayEquals(new String[]{"/tasks"},
                create.getAnnotation(PostMapping.class).value());
    }

    @Test
    void checksTheTraceProjectAndRecordsTheSessionAccountAsCreator() {
        TraceWorkflowCandidateApplicationService service =
                mock(TraceWorkflowCandidateApplicationService.class);
        TraceWorkflowCandidateController controller =
                new TraceWorkflowCandidateController(service);
        EligibilityView eligibility = new EligibilityView(
                TraceWorkflowCandidateEligibility.ELIGIBILITY_SCHEMA,
                "trace-1", "orders", true, List.of(), List.of(),
                "wf-1", 22L, "v1", JsonNodeFactory.instance.objectNode());
        CandidateTaskView view = new CandidateTaskView(
                "reachai.runops.trace-workflow-candidate-task.v1",
                true, eligibility, null);
        when(service.eligibility("trace-1")).thenReturn(eligibility);
        when(service.createOrReuse("trace-1", new CreateCandidateTaskRequest("CODEX", "operator"))).thenReturn(view);

        var reader = request(new com.enterprise.ai.control.identity.PlatformPermissionGrant("runops:read", "PROJECT", "orders"));
        assertEquals(eligibility, controller.eligibility(reader, "trace-1").getBody());
        var denied = org.junit.jupiter.api.Assertions.assertThrows(org.springframework.web.server.ResponseStatusException.class,
                () -> controller.createTask(reader, "trace-1", new CreateCandidateTaskRequest("CODEX", "alice")));
        assertEquals(403, denied.getStatusCode().value());

        var writer = request(new com.enterprise.ai.control.identity.PlatformPermissionGrant("runops:read", "PROJECT", "orders"),
                new com.enterprise.ai.control.identity.PlatformPermissionGrant("workflow:write", "PROJECT", "orders"));
        assertEquals(view, controller.createTask(writer, "trace-1", new CreateCandidateTaskRequest("CODEX", "alice")).getBody());
        verify(service).createOrReuse("trace-1", new CreateCandidateTaskRequest("CODEX", "operator"));

        var otherProject = request(new com.enterprise.ai.control.identity.PlatformPermissionGrant("runops:read", "PROJECT", "billing"));
        org.junit.jupiter.api.Assertions.assertThrows(org.springframework.web.server.ResponseStatusException.class,
                () -> controller.eligibility(otherProject, "trace-1"));
    }

    private static org.springframework.mock.web.MockHttpServletRequest request(
            com.enterprise.ai.control.identity.PlatformPermissionGrant... grants) {
        var request = new org.springframework.mock.web.MockHttpServletRequest();
        request.setAttribute(com.enterprise.ai.control.identity.PlatformConsoleAuthInterceptor.SESSION_REQUEST_ATTRIBUTE,
                new com.enterprise.ai.control.identity.PlatformAuthenticatedSession(
                        new com.enterprise.ai.control.identity.PlatformPrincipal(42L, "operator", "操作员"), "session", null,
                        List.of(), java.util.Arrays.stream(grants).map(com.enterprise.ai.control.identity.PlatformPermissionGrant::permissionCode).toList(),
                        List.of(grants)));
        return request;
    }
}
