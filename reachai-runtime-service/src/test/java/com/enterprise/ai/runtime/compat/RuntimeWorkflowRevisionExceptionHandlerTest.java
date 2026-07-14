package com.enterprise.ai.runtime.compat;

import com.enterprise.ai.runtime.workflow.RuntimeWorkflowDebugService;
import com.enterprise.ai.runtime.workflow.RuntimeWorkflowDefinitionService;
import com.enterprise.ai.runtime.workflow.RuntimeWorkflowReleaseValidationService;
import com.enterprise.ai.runtime.workflow.RuntimeWorkflowRevisionConflictException;
import com.enterprise.ai.runtime.workflow.RuntimeWorkflowRevisionFormatException;
import com.enterprise.ai.runtime.workflow.RuntimeWorkflowStudioService;
import com.enterprise.ai.runtime.workflow.draft.RuntimeWorkflowDraftEditService;
import com.enterprise.ai.runtime.workflow.draft.RuntimeWorkflowDraftGenerationService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class RuntimeWorkflowRevisionExceptionHandlerTest {

    @Test
    void returnsStructuredConflictWithCurrentRevisionEtag() throws Exception {
        RuntimeWorkflowStudioService studioService = mock(RuntimeWorkflowStudioService.class);
        when(studioService.saveStudioDraft(eq("wf-1"), any()))
                .thenThrow(new RuntimeWorkflowRevisionConflictException(
                        "wf-1", "2026-07-14T10:30:00", "2026-07-14T10:31:00"));

        mockMvc(studioService)
                .perform(put("/api/workflows/wf-1/studio")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"graphSpecJson\":\"{}\"}"))
                .andExpect(status().isConflict())
                .andExpect(header().string(HttpHeaders.ETAG, "\"2026-07-14T10:31:00\""))
                .andExpect(jsonPath("$.code").value("WORKFLOW_DRAFT_CONFLICT"))
                .andExpect(jsonPath("$.workflowId").value("wf-1"))
                .andExpect(jsonPath("$.baseRevision").value("2026-07-14T10:30:00"))
                .andExpect(jsonPath("$.currentRevision").value("2026-07-14T10:31:00"));
    }

    @Test
    void returnsBadRequestForMalformedBaseRevision() throws Exception {
        RuntimeWorkflowStudioService studioService = mock(RuntimeWorkflowStudioService.class);
        when(studioService.saveStudioDraft(eq("wf-1"), any()))
                .thenThrow(new RuntimeWorkflowRevisionFormatException(
                        "baseRevision must be an ISO-8601 workflow updatedAt value",
                        new IllegalArgumentException("invalid revision")));

        mockMvc(studioService)
                .perform(put("/api/workflows/wf-1/studio")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"graphSpecJson\":\"{}\",\"baseRevision\":\"not-a-date\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("WORKFLOW_BASE_REVISION_INVALID"));
    }

    private MockMvc mockMvc(RuntimeWorkflowStudioService studioService) {
        RuntimeWorkflowCompatibilityController controller = new RuntimeWorkflowCompatibilityController(
                mock(RuntimeWorkflowDefinitionService.class),
                mock(RuntimeWorkflowReleaseValidationService.class),
                studioService,
                mock(RuntimeWorkflowDebugService.class),
                mock(RuntimeWorkflowDraftGenerationService.class),
                mock(RuntimeWorkflowDraftEditService.class));
        return MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new RuntimeWorkflowRevisionExceptionHandler())
                .build();
    }
}
