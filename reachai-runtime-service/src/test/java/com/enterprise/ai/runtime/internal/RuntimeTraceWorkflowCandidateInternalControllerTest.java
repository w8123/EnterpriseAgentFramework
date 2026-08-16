package com.enterprise.ai.runtime.internal;

import com.enterprise.ai.agent.graph.GraphSpec;
import com.enterprise.ai.runtime.workflow.RuntimeTraceWorkflowCandidateDraftService;
import com.enterprise.ai.runtime.workflow.RuntimeTraceWorkflowCandidateDraftService.DraftRequest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class RuntimeTraceWorkflowCandidateInternalControllerTest {

    @Test
    void exposesRuntimeOwnedDraftEndpointAndDelegates() throws Exception {
        Method method = RuntimeTraceWorkflowCandidateInternalController.class
                .getDeclaredMethod("createOrReplace", DraftRequest.class);
        assertArrayEquals(
                new String[]{
                        "/internal/runtime/runops/workflow-candidates/drafts"
                },
                method.getAnnotation(PostMapping.class).value());

        RuntimeTraceWorkflowCandidateDraftService service =
                mock(RuntimeTraceWorkflowCandidateDraftService.class);
        RuntimeTraceWorkflowCandidateInternalController controller =
                new RuntimeTraceWorkflowCandidateInternalController(service);
        DraftRequest request = new DraftRequest(
                "ait-1",
                "trace-1",
                "wf-source",
                22L,
                "v1.0.1",
                7L,
                "orders",
                "candidate",
                "candidate-ait1",
                null,
                "GENERAL",
                null,
                new GraphSpec(),
                null);

        assertEquals(HttpStatus.OK,
                controller.createOrReplace(request).getStatusCode());
        verify(service).createOrReplace(request);
    }
}
