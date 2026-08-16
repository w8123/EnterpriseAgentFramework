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
                .getDeclaredMethod("eligibility", String.class);
        Method create = TraceWorkflowCandidateController.class
                .getDeclaredMethod("createTask", String.class,
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
    void delegatesToApplicationService() {
        TraceWorkflowCandidateApplicationService service =
                mock(TraceWorkflowCandidateApplicationService.class);
        TraceWorkflowCandidateController controller =
                new TraceWorkflowCandidateController(service);
        EligibilityView eligibility = new EligibilityView(
                TraceWorkflowCandidateEligibility.ELIGIBILITY_SCHEMA,
                "trace-1", "orders", true, List.of(), List.of(),
                "wf-1", 22L, "v1", JsonNodeFactory.instance.objectNode());
        CreateCandidateTaskRequest request =
                new CreateCandidateTaskRequest("CODEX", "alice");
        CandidateTaskView view = new CandidateTaskView(
                "reachai.runops.trace-workflow-candidate-task.v1",
                true, eligibility, null);
        when(service.eligibility("trace-1")).thenReturn(eligibility);
        when(service.createOrReuse("trace-1", request)).thenReturn(view);

        assertEquals(eligibility, controller.eligibility("trace-1").getBody());
        assertEquals(view, controller.createTask("trace-1", request).getBody());
        verify(service).eligibility("trace-1");
        verify(service).createOrReuse("trace-1", request);
    }
}
