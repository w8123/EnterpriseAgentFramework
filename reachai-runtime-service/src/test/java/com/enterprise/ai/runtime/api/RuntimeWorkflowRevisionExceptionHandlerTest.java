package com.enterprise.ai.runtime.api;

import com.enterprise.ai.runtime.workflow.RuntimeWorkflowDebugService;
import com.enterprise.ai.runtime.workflow.RuntimeWorkflowManagementService;
import com.enterprise.ai.runtime.workflow.RuntimeWorkflowRevisionConflictException;
import com.enterprise.ai.runtime.workflow.RuntimeWorkflowRevisionFormatException;
import com.enterprise.ai.runtime.workflow.RuntimeWorkflowKeySlugConflictException;
import com.enterprise.ai.runtime.workflow.RuntimeWorkflowStudioService;
import com.enterprise.ai.runtime.workflow.proposal.RuntimeWorkflowProposalEditService;
import com.enterprise.ai.runtime.workflow.proposal.RuntimeWorkflowProposalGenerationService;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class RuntimeWorkflowRevisionExceptionHandlerTest {

    @Test
    void returnsClearConflictWhenWorkflowKeyAlreadyExists() throws Exception {
        RuntimeWorkflowManagementService workflowService = mock(RuntimeWorkflowManagementService.class);
        when(workflowService.create(any())).thenThrow(new RuntimeWorkflowKeySlugConflictException("1111"));
        RuntimeWorkflowPublicController controller = new RuntimeWorkflowPublicController(
                workflowService,
                mock(RuntimeWorkflowStudioService.class),
                mock(RuntimeWorkflowDebugService.class),
                mock(RuntimeWorkflowProposalGenerationService.class),
                mock(RuntimeWorkflowProposalEditService.class));

        MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new RuntimeWorkflowRevisionExceptionHandler())
                .build()
                .perform(post("/api/workflows")
                        .accept(MediaType.APPLICATION_JSON)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"keySlug\":\"1111\",\"name\":\"测试问答\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("WORKFLOW_KEY_SLUG_EXISTS"))
                .andExpect(jsonPath("$.keySlug").value("1111"));
    }

    @Test
    void returnsStructuredConflictWithCurrentRevisionEtag() throws Exception {
        RuntimeWorkflowStudioService studioService = mock(RuntimeWorkflowStudioService.class);
        when(studioService.saveWorkingCopy(eq("wf-1"), any()))
                .thenThrow(new RuntimeWorkflowRevisionConflictException(
                        "wf-1", "2026-07-14T10:30:00", "2026-07-14T10:31:00"));

        mockMvc(studioService)
                .perform(put("/api/workflows/wf-1/working-copy")
                        .accept(MediaType.APPLICATION_JSON)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"graphSpecJson\":\"{}\"}"))
                .andExpect(status().isConflict())
                .andExpect(header().string(HttpHeaders.ETAG, "\"2026-07-14T10:31:00\""))
                .andExpect(jsonPath("$.code").value("WORKFLOW_WORKING_COPY_CONFLICT"))
                .andExpect(jsonPath("$.workflowId").value("wf-1"))
                .andExpect(jsonPath("$.baseRevision").value("2026-07-14T10:30:00"))
                .andExpect(jsonPath("$.currentRevision").value("2026-07-14T10:31:00"));
    }

    @Test
    void returnsBadRequestForMalformedBaseRevision() throws Exception {
        RuntimeWorkflowStudioService studioService = mock(RuntimeWorkflowStudioService.class);
        when(studioService.saveWorkingCopy(eq("wf-1"), any()))
                .thenThrow(new RuntimeWorkflowRevisionFormatException(
                        "baseRevision must be an ISO-8601 workflow updatedAt value",
                        new IllegalArgumentException("invalid revision")));

        mockMvc(studioService)
                .perform(put("/api/workflows/wf-1/working-copy")
                        .accept(MediaType.APPLICATION_JSON)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"graphSpecJson\":\"{}\",\"baseRevision\":\"not-a-date\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("WORKFLOW_BASE_REVISION_INVALID"));
    }

    private MockMvc mockMvc(RuntimeWorkflowStudioService studioService) {
        RuntimeWorkflowPublicController controller = new RuntimeWorkflowPublicController(
                mock(RuntimeWorkflowManagementService.class),
                studioService,
                mock(RuntimeWorkflowDebugService.class),
                mock(RuntimeWorkflowProposalGenerationService.class),
                mock(RuntimeWorkflowProposalEditService.class));
        return MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new RuntimeWorkflowRevisionExceptionHandler())
                .build();
    }
}
