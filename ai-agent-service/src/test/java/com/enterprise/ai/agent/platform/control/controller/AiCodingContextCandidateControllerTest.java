package com.enterprise.ai.agent.platform.control.controller;

import com.enterprise.ai.agent.aicoding.AiCodingContextCandidateSubmissionService;
import com.enterprise.ai.agent.platform.control.context.memory.ContextMemoryCandidateCreateRequest;
import com.enterprise.ai.agent.platform.control.context.memory.ContextMemoryCandidateQueryRequest;
import com.enterprise.ai.agent.platform.control.context.memory.ContextMemoryCandidateResponse;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AiCodingContextCandidateControllerTest {

    @Test
    void createCandidateDelegatesToAiCodingSubmissionService() {
        AiCodingContextCandidateSubmissionService submissionService =
                mock(AiCodingContextCandidateSubmissionService.class);
        AiCodingContextCandidateController controller =
                new AiCodingContextCandidateController(submissionService);

        ContextMemoryCandidateCreateRequest request = new ContextMemoryCandidateCreateRequest();
        when(submissionService.createCandidate(7L, request)).thenReturn(
                ContextMemoryCandidateResponse.builder().id(100L).build());

        var response = controller.createCandidate(7L, request);

        assertEquals(100L, response.getBody().getId());
        verify(submissionService).createCandidate(7L, request);
    }

    @Test
    void createCandidateBatchDelegatesToAiCodingSubmissionService() {
        AiCodingContextCandidateSubmissionService submissionService =
                mock(AiCodingContextCandidateSubmissionService.class);
        AiCodingContextCandidateController controller =
                new AiCodingContextCandidateController(submissionService);

        List<ContextMemoryCandidateCreateRequest> requests = List.of(new ContextMemoryCandidateCreateRequest());
        when(submissionService.createCandidateBatch(7L, requests)).thenReturn(List.of(
                ContextMemoryCandidateResponse.builder().id(201L).build(),
                ContextMemoryCandidateResponse.builder().id(202L).build()));

        var response = controller.createCandidateBatch(7L, requests);

        assertEquals(2, response.getBody().size());
        verify(submissionService).createCandidateBatch(7L, requests);
    }

    @Test
    void listCandidatesDelegatesToAiCodingSubmissionService() {
        AiCodingContextCandidateSubmissionService submissionService =
                mock(AiCodingContextCandidateSubmissionService.class);
        AiCodingContextCandidateController controller =
                new AiCodingContextCandidateController(submissionService);

        ContextMemoryCandidateQueryRequest query = new ContextMemoryCandidateQueryRequest();
        when(submissionService.listCandidates(7L, query)).thenReturn(List.of());

        var response = controller.listCandidates(7L, query);

        assertEquals(0, response.getBody().size());
        verify(submissionService).listCandidates(7L, query);
    }
}
