package com.enterprise.ai.agent.aicoding;

import com.enterprise.ai.agent.platform.control.context.ContextSourceType;
import com.enterprise.ai.agent.platform.control.context.ContextVisibility;
import com.enterprise.ai.agent.platform.control.context.MemoryLane;
import com.enterprise.ai.agent.platform.control.context.memory.ContextMemoryCandidateCreateRequest;
import com.enterprise.ai.agent.platform.control.context.memory.ContextMemoryCandidateQueryRequest;
import com.enterprise.ai.agent.platform.control.context.memory.ContextMemoryCandidateResponse;
import com.enterprise.ai.agent.platform.control.context.memory.ContextMemoryCandidateService;
import com.enterprise.ai.agent.platform.control.context.memory.ContextMemoryCandidateType;
import com.enterprise.ai.agent.capability.catalog.scan.ScanProjectEntity;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AiCodingContextCandidateSubmissionServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void createCandidateForcesProjectDevScopeWithoutMutatingClientRequest() {
        AiCodingAccessGuard accessGuard = mock(AiCodingAccessGuard.class);
        ContextMemoryCandidateService candidateService = mock(ContextMemoryCandidateService.class);
        AiCodingContextCandidateSubmissionService submissionService =
                new AiCodingContextCandidateSubmissionService(accessGuard, candidateService, objectMapper);

        ScanProjectEntity project = project(7L, "orders");
        when(accessGuard.requireProjectAccess(7L)).thenReturn(project);
        when(accessGuard.auditActorLabel(7L)).thenReturn("aiCodingKey:7");
        when(candidateService.createCandidate(any())).thenReturn(
                ContextMemoryCandidateResponse.builder().id(100L).build());

        ContextMemoryCandidateCreateRequest request = new ContextMemoryCandidateCreateRequest();
        request.setTenantId("tenant-b");
        request.setMemoryLane(MemoryLane.RUNTIME_USER.name());
        request.setProjectId(99L);
        request.setProjectCode("wrong-project");
        request.setSourceType("");
        request.setVisibility(ContextVisibility.GLOBAL.name());
        request.setProposedBy("rac_secret_must_not_be_stored");
        request.setUserId("runtime-user");
        request.setGlobalUserId("global-user");
        request.setExternalUserId("external-user");
        request.setContent("Orders workflow reads API ownership rules from source code");

        submissionService.createCandidate(7L, request);

        ArgumentCaptor<ContextMemoryCandidateCreateRequest> captor =
                ArgumentCaptor.forClass(ContextMemoryCandidateCreateRequest.class);
        verify(candidateService).createCandidate(captor.capture());
        ContextMemoryCandidateCreateRequest submitted = captor.getValue();
        assertEquals("default", submitted.getTenantId());
        assertEquals(MemoryLane.PROJECT_DEV.name(), submitted.getMemoryLane());
        assertEquals(7L, submitted.getProjectId());
        assertEquals("orders", submitted.getProjectCode());
        assertEquals(ContextSourceType.CODE.name(), submitted.getSourceType());
        assertEquals(ContextMemoryCandidateType.NOTE.name(), submitted.getCandidateType());
        assertEquals(ContextVisibility.PROJECT.name(), submitted.getVisibility());
        assertEquals("aiCodingKey:7", submitted.getProposedBy());
        assertFalse(submitted.getProposedBy().contains("rac_secret"));
        assertNull(submitted.getUserId());
        assertNull(submitted.getGlobalUserId());
        assertNull(submitted.getExternalUserId());

        assertEquals("tenant-b", request.getTenantId());
        assertEquals(MemoryLane.RUNTIME_USER.name(), request.getMemoryLane());
        assertEquals(99L, request.getProjectId());
        assertEquals("wrong-project", request.getProjectCode());
        assertEquals(ContextVisibility.GLOBAL.name(), request.getVisibility());
        assertEquals("rac_secret_must_not_be_stored", request.getProposedBy());
        assertEquals("runtime-user", request.getUserId());
        assertEquals("global-user", request.getGlobalUserId());
        assertEquals("external-user", request.getExternalUserId());
    }

    @Test
    void createCandidateAddsSubmissionTraceDefaultsAndMetadata() throws Exception {
        AiCodingAccessGuard accessGuard = mock(AiCodingAccessGuard.class);
        ContextMemoryCandidateService candidateService = mock(ContextMemoryCandidateService.class);
        AiCodingContextCandidateSubmissionService submissionService =
                new AiCodingContextCandidateSubmissionService(accessGuard, candidateService, objectMapper);

        ScanProjectEntity project = project(7L, "orders");
        when(accessGuard.requireProjectAccess(7L)).thenReturn(project);
        when(accessGuard.auditActorLabel(7L)).thenReturn("aiCodingKey:7");
        when(candidateService.createCandidate(any())).thenReturn(
                ContextMemoryCandidateResponse.builder().id(100L).build());

        ContextMemoryCandidateCreateRequest request = new ContextMemoryCandidateCreateRequest();
        request.setContent("Orders workflow reads API ownership rules from source code");

        submissionService.createCandidate(7L, request);

        ArgumentCaptor<ContextMemoryCandidateCreateRequest> captor =
                ArgumentCaptor.forClass(ContextMemoryCandidateCreateRequest.class);
        verify(candidateService).createCandidate(captor.capture());
        ContextMemoryCandidateCreateRequest submitted = captor.getValue();
        assertTrue(submitted.getTraceId().startsWith("ai-coding-submission-"));
        assertEquals(submitted.getTraceId(), submitted.getSessionId());
        assertEquals("AI_CODING_CONTEXT_SCAN", submitted.getOrigin());

        Map<String, Object> metadata = readObject(submitted.getMetadataJson());
        Map<String, Object> submission = readObject(metadata.get("aiCodingSubmission"));
        assertEquals("reachai.ai-coding.submission.v1", submission.get("schema"));
        assertEquals(submitted.getTraceId(), submission.get("submissionId"));
        assertEquals("CONTEXT_CANDIDATES", submission.get("entrypoint"));
        assertEquals(7L, ((Number) submission.get("projectId")).longValue());
        assertEquals("orders", submission.get("projectCode"));
        assertEquals("PROJECT_DEV", submission.get("memoryLane"));
    }

    @Test
    void createCandidateOverridesClientOriginForGatewayAttribution() {
        AiCodingAccessGuard accessGuard = mock(AiCodingAccessGuard.class);
        ContextMemoryCandidateService candidateService = mock(ContextMemoryCandidateService.class);
        AiCodingContextCandidateSubmissionService submissionService =
                new AiCodingContextCandidateSubmissionService(accessGuard, candidateService, objectMapper);

        ScanProjectEntity project = project(7L, "orders");
        when(accessGuard.requireProjectAccess(7L)).thenReturn(project);
        when(accessGuard.auditActorLabel(7L)).thenReturn("aiCodingKey:7");
        when(candidateService.createCandidate(any())).thenReturn(
                ContextMemoryCandidateResponse.builder().id(100L).build());

        ContextMemoryCandidateCreateRequest request = new ContextMemoryCandidateCreateRequest();
        request.setContent("Orders workflow reads API ownership rules from source code");
        request.setOrigin("CURSOR_CUSTOM_SCAN");

        submissionService.createCandidate(7L, request);

        ArgumentCaptor<ContextMemoryCandidateCreateRequest> captor =
                ArgumentCaptor.forClass(ContextMemoryCandidateCreateRequest.class);
        verify(candidateService).createCandidate(captor.capture());
        assertEquals("AI_CODING_CONTEXT_SCAN", captor.getValue().getOrigin());
        assertEquals("CURSOR_CUSTOM_SCAN", request.getOrigin());
    }

    @Test
    void createCandidateGeneratesServerSubmissionIdAndPreservesClientTraceInMetadata() throws Exception {
        AiCodingAccessGuard accessGuard = mock(AiCodingAccessGuard.class);
        ContextMemoryCandidateService candidateService = mock(ContextMemoryCandidateService.class);
        AiCodingContextCandidateSubmissionService submissionService =
                new AiCodingContextCandidateSubmissionService(accessGuard, candidateService, objectMapper);

        ScanProjectEntity project = project(7L, "orders");
        when(accessGuard.requireProjectAccess(7L)).thenReturn(project);
        when(accessGuard.auditActorLabel(7L)).thenReturn("aiCodingKey:7");
        when(candidateService.createCandidate(any())).thenReturn(
                ContextMemoryCandidateResponse.builder().id(100L).build());

        ContextMemoryCandidateCreateRequest request = new ContextMemoryCandidateCreateRequest();
        request.setContent("Orders workflow reads API ownership rules from source code");
        request.setTraceId("cursor-scan-run-1");

        submissionService.createCandidate(7L, request);

        ArgumentCaptor<ContextMemoryCandidateCreateRequest> captor =
                ArgumentCaptor.forClass(ContextMemoryCandidateCreateRequest.class);
        verify(candidateService).createCandidate(captor.capture());
        ContextMemoryCandidateCreateRequest submitted = captor.getValue();
        assertTrue(submitted.getTraceId().startsWith("ai-coding-submission-"));
        assertEquals(submitted.getTraceId(), submitted.getSessionId());
        assertEquals("cursor-scan-run-1", request.getTraceId());

        Map<String, Object> metadata = readObject(submitted.getMetadataJson());
        Map<String, Object> submission = readObject(metadata.get("aiCodingSubmission"));
        assertEquals(submitted.getTraceId(), submission.get("submissionId"));
        assertEquals("cursor-scan-run-1", submission.get("clientTraceId"));
    }

    @Test
    void createCandidateOverridesClientSessionIdAndPreservesItInMetadata() throws Exception {
        AiCodingAccessGuard accessGuard = mock(AiCodingAccessGuard.class);
        ContextMemoryCandidateService candidateService = mock(ContextMemoryCandidateService.class);
        AiCodingContextCandidateSubmissionService submissionService =
                new AiCodingContextCandidateSubmissionService(accessGuard, candidateService, objectMapper);

        ScanProjectEntity project = project(7L, "orders");
        when(accessGuard.requireProjectAccess(7L)).thenReturn(project);
        when(accessGuard.auditActorLabel(7L)).thenReturn("aiCodingKey:7");
        when(candidateService.createCandidate(any())).thenReturn(
                ContextMemoryCandidateResponse.builder().id(100L).build());

        ContextMemoryCandidateCreateRequest request = new ContextMemoryCandidateCreateRequest();
        request.setContent("Orders workflow reads API ownership rules from source code");
        request.setSessionId("cursor-session-1");

        submissionService.createCandidate(7L, request);

        ArgumentCaptor<ContextMemoryCandidateCreateRequest> captor =
                ArgumentCaptor.forClass(ContextMemoryCandidateCreateRequest.class);
        verify(candidateService).createCandidate(captor.capture());
        ContextMemoryCandidateCreateRequest submitted = captor.getValue();
        assertEquals(submitted.getTraceId(), submitted.getSessionId());
        assertEquals("cursor-session-1", request.getSessionId());

        Map<String, Object> metadata = readObject(submitted.getMetadataJson());
        Map<String, Object> submission = readObject(metadata.get("aiCodingSubmission"));
        assertEquals(submitted.getSessionId(), submission.get("submissionId"));
        assertEquals("cursor-session-1", submission.get("clientSessionId"));
    }

    @Test
    void createCandidateDoesNotLetExternalToolControlGovernanceWeightsOrTtl() throws Exception {
        AiCodingAccessGuard accessGuard = mock(AiCodingAccessGuard.class);
        ContextMemoryCandidateService candidateService = mock(ContextMemoryCandidateService.class);
        AiCodingContextCandidateSubmissionService submissionService =
                new AiCodingContextCandidateSubmissionService(accessGuard, candidateService, objectMapper);

        ScanProjectEntity project = project(7L, "orders");
        when(accessGuard.requireProjectAccess(7L)).thenReturn(project);
        when(accessGuard.auditActorLabel(7L)).thenReturn("aiCodingKey:7");
        when(candidateService.createCandidate(any())).thenReturn(
                ContextMemoryCandidateResponse.builder().id(100L).build());

        LocalDateTime clientExpiresAt = LocalDateTime.of(2099, 1, 1, 0, 0);
        ContextMemoryCandidateCreateRequest request = new ContextMemoryCandidateCreateRequest();
        request.setContent("Orders workflow reads API ownership rules from source code");
        request.setConfidence(new BigDecimal("1.0000"));
        request.setTrustLevel("VERIFIED");
        request.setExpiresAt(clientExpiresAt);

        submissionService.createCandidate(7L, request);

        ArgumentCaptor<ContextMemoryCandidateCreateRequest> captor =
                ArgumentCaptor.forClass(ContextMemoryCandidateCreateRequest.class);
        verify(candidateService).createCandidate(captor.capture());
        ContextMemoryCandidateCreateRequest submitted = captor.getValue();
        assertNull(submitted.getConfidence());
        assertNull(submitted.getTrustLevel());
        assertNull(submitted.getExpiresAt());
        assertEquals(new BigDecimal("1.0000"), request.getConfidence());
        assertEquals("VERIFIED", request.getTrustLevel());
        assertEquals(clientExpiresAt, request.getExpiresAt());

        Map<String, Object> metadata = readObject(submitted.getMetadataJson());
        Map<String, Object> submission = readObject(metadata.get("aiCodingSubmission"));
        assertEquals("1.0000", submission.get("clientConfidence"));
        assertEquals("VERIFIED", submission.get("clientTrustLevel"));
        assertEquals("2099-01-01T00:00", submission.get("clientExpiresAt"));
    }

    @Test
    void createCandidateBatchNormalizesEverySubmission() throws Exception {
        AiCodingAccessGuard accessGuard = mock(AiCodingAccessGuard.class);
        ContextMemoryCandidateService candidateService = mock(ContextMemoryCandidateService.class);
        AiCodingContextCandidateSubmissionService submissionService =
                new AiCodingContextCandidateSubmissionService(accessGuard, candidateService, objectMapper);

        ScanProjectEntity project = project(7L, "orders");
        when(accessGuard.requireProjectAccess(7L)).thenReturn(project);
        when(accessGuard.auditActorLabel(7L)).thenReturn("aiCodingKey:7");
        when(candidateService.createCandidates(any())).thenReturn(List.of());

        ContextMemoryCandidateCreateRequest apiContext = new ContextMemoryCandidateCreateRequest();
        apiContext.setContent("POST /orders/{id}/approve checks owner permission");
        apiContext.setCandidateType(ContextMemoryCandidateType.API_CONTEXT.name());
        ContextMemoryCandidateCreateRequest workflowContext = new ContextMemoryCandidateCreateRequest();
        workflowContext.setContent("Workflow status transition comes from graph spec");
        workflowContext.setCandidateType(ContextMemoryCandidateType.WORKFLOW_CONTEXT.name());
        workflowContext.setSourceType(ContextSourceType.DOC.name());
        workflowContext.setTraceId("cursor-scan-run-1");
        workflowContext.setMetadataJson("{\"toolName\":\"Cursor\"}");

        submissionService.createCandidateBatch(7L, Arrays.asList(apiContext, workflowContext));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ContextMemoryCandidateCreateRequest>> captor =
                ArgumentCaptor.forClass(List.class);
        verify(candidateService).createCandidates(captor.capture());
        List<ContextMemoryCandidateCreateRequest> submitted = captor.getValue();
        assertEquals(2, submitted.size());
        String submissionId = submitted.get(0).getTraceId();
        assertTrue(submissionId.startsWith("ai-coding-submission-"));
        for (ContextMemoryCandidateCreateRequest item : submitted) {
            assertEquals("default", item.getTenantId());
            assertEquals(MemoryLane.PROJECT_DEV.name(), item.getMemoryLane());
            assertEquals(7L, item.getProjectId());
            assertEquals("orders", item.getProjectCode());
            assertEquals("aiCodingKey:7", item.getProposedBy());
            assertEquals(submissionId, item.getTraceId());
            assertEquals(submissionId, item.getSessionId());
            assertEquals("AI_CODING_CONTEXT_SCAN", item.getOrigin());
            Map<String, Object> metadata = readObject(item.getMetadataJson());
            Map<String, Object> submission = readObject(metadata.get("aiCodingSubmission"));
            assertEquals(submissionId, submission.get("submissionId"));
        }
        assertEquals(ContextMemoryCandidateType.API_CONTEXT.name(), submitted.get(0).getCandidateType());
        assertEquals(ContextSourceType.CODE.name(), submitted.get(0).getSourceType());
        assertEquals(ContextMemoryCandidateType.WORKFLOW_CONTEXT.name(), submitted.get(1).getCandidateType());
        assertEquals(ContextSourceType.DOC.name(), submitted.get(1).getSourceType());
        Map<String, Object> workflowMetadata = readObject(submitted.get(1).getMetadataJson());
        assertEquals("Cursor", workflowMetadata.get("toolName"));
        assertEquals("cursor-scan-run-1", readObject(workflowMetadata.get("aiCodingSubmission")).get("clientTraceId"));
    }

    @Test
    void createCandidateBatchRejectsNullItemBeforeDelegatingToCandidateService() {
        AiCodingAccessGuard accessGuard = mock(AiCodingAccessGuard.class);
        ContextMemoryCandidateService candidateService = mock(ContextMemoryCandidateService.class);
        AiCodingContextCandidateSubmissionService submissionService =
                new AiCodingContextCandidateSubmissionService(accessGuard, candidateService, objectMapper);

        ScanProjectEntity project = project(7L, "orders");
        when(accessGuard.requireProjectAccess(7L)).thenReturn(project);
        when(accessGuard.auditActorLabel(7L)).thenReturn("aiCodingKey:7");

        ContextMemoryCandidateCreateRequest valid = new ContextMemoryCandidateCreateRequest();
        valid.setContent("POST /orders/{id}/approve checks owner permission");

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> submissionService.createCandidateBatch(7L, Arrays.asList(valid, null)));

        assertTrue(ex.getMessage().contains("content is required"));
        verify(candidateService, never()).createCandidates(any());
    }

    @Test
    void listCandidatesForcesProjectDevScope() {
        AiCodingAccessGuard accessGuard = mock(AiCodingAccessGuard.class);
        ContextMemoryCandidateService candidateService = mock(ContextMemoryCandidateService.class);
        AiCodingContextCandidateSubmissionService submissionService =
                new AiCodingContextCandidateSubmissionService(accessGuard, candidateService, objectMapper);

        ScanProjectEntity project = project(7L, "orders");
        when(accessGuard.requireProjectAccess(7L)).thenReturn(project);
        when(candidateService.listCandidates(any())).thenReturn(List.of());

        ContextMemoryCandidateQueryRequest query = new ContextMemoryCandidateQueryRequest();
        query.setTenantId("tenant-b");
        query.setMemoryLane(MemoryLane.RUNTIME_USER.name());
        query.setProjectId(99L);
        query.setProjectCode("wrong-project");
        query.setTraceId("ai-coding-submission-123");

        submissionService.listCandidates(7L, query);

        ArgumentCaptor<ContextMemoryCandidateQueryRequest> captor =
                ArgumentCaptor.forClass(ContextMemoryCandidateQueryRequest.class);
        verify(candidateService).listCandidates(captor.capture());
        ContextMemoryCandidateQueryRequest submitted = captor.getValue();
        assertEquals("default", submitted.getTenantId());
        assertEquals(MemoryLane.PROJECT_DEV.name(), submitted.getMemoryLane());
        assertEquals(7L, submitted.getProjectId());
        assertEquals("orders", submitted.getProjectCode());
        assertEquals("ai-coding-submission-123", submitted.getTraceId());
        assertEquals("PENDING", submitted.getStatus());

        assertEquals("tenant-b", query.getTenantId());
        assertEquals(MemoryLane.RUNTIME_USER.name(), query.getMemoryLane());
        assertEquals(99L, query.getProjectId());
        assertEquals("wrong-project", query.getProjectCode());
        assertEquals("ai-coding-submission-123", query.getTraceId());
    }

    @Test
    void listCandidatesTrimsSubmissionTraceIdBeforeDelegating() {
        AiCodingAccessGuard accessGuard = mock(AiCodingAccessGuard.class);
        ContextMemoryCandidateService candidateService = mock(ContextMemoryCandidateService.class);
        AiCodingContextCandidateSubmissionService submissionService =
                new AiCodingContextCandidateSubmissionService(accessGuard, candidateService, objectMapper);

        ScanProjectEntity project = project(7L, "orders");
        when(accessGuard.requireProjectAccess(7L)).thenReturn(project);
        when(candidateService.listCandidates(any())).thenReturn(List.of());

        ContextMemoryCandidateQueryRequest query = new ContextMemoryCandidateQueryRequest();
        query.setTraceId("  ai-coding-submission-123  ");

        submissionService.listCandidates(7L, query);

        ArgumentCaptor<ContextMemoryCandidateQueryRequest> captor =
                ArgumentCaptor.forClass(ContextMemoryCandidateQueryRequest.class);
        verify(candidateService).listCandidates(captor.capture());
        assertEquals("ai-coding-submission-123", captor.getValue().getTraceId());
        assertEquals("  ai-coding-submission-123  ", query.getTraceId());
    }

    @Test
    void listCandidatesRequiresSubmissionTraceIdBeforeDelegating() {
        AiCodingAccessGuard accessGuard = mock(AiCodingAccessGuard.class);
        ContextMemoryCandidateService candidateService = mock(ContextMemoryCandidateService.class);
        AiCodingContextCandidateSubmissionService submissionService =
                new AiCodingContextCandidateSubmissionService(accessGuard, candidateService, objectMapper);

        when(accessGuard.requireProjectAccess(7L)).thenReturn(project(7L, "orders"));

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> submissionService.listCandidates(7L, new ContextMemoryCandidateQueryRequest()));

        assertTrue(ex.getMessage().contains("traceId is required"));
        verify(candidateService, never()).listCandidates(any());
    }

    @Test
    void listCandidatesRejectsNonPendingStatusForExternalStatusCheck() {
        AiCodingAccessGuard accessGuard = mock(AiCodingAccessGuard.class);
        ContextMemoryCandidateService candidateService = mock(ContextMemoryCandidateService.class);
        AiCodingContextCandidateSubmissionService submissionService =
                new AiCodingContextCandidateSubmissionService(accessGuard, candidateService, objectMapper);

        when(accessGuard.requireProjectAccess(7L)).thenReturn(project(7L, "orders"));
        ContextMemoryCandidateQueryRequest query = new ContextMemoryCandidateQueryRequest();
        query.setTraceId("ai-coding-submission-123");
        query.setStatus("APPROVED");

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> submissionService.listCandidates(7L, query));

        assertTrue(ex.getMessage().contains("Only PENDING status checks are allowed"));
        verify(candidateService, never()).listCandidates(any());
    }

    @Test
    void listCandidatesRejectsClientTraceIdWithoutServerSubmissionPrefix() {
        AiCodingAccessGuard accessGuard = mock(AiCodingAccessGuard.class);
        ContextMemoryCandidateService candidateService = mock(ContextMemoryCandidateService.class);
        AiCodingContextCandidateSubmissionService submissionService =
                new AiCodingContextCandidateSubmissionService(accessGuard, candidateService, objectMapper);

        when(accessGuard.requireProjectAccess(7L)).thenReturn(project(7L, "orders"));
        ContextMemoryCandidateQueryRequest query = new ContextMemoryCandidateQueryRequest();
        query.setTraceId("cursor-scan-run-1");

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> submissionService.listCandidates(7L, query));

        assertTrue(ex.getMessage().contains("traceId must be a ReachAI submissionId"));
        verify(candidateService, never()).listCandidates(any());
    }

    private ScanProjectEntity project(Long id, String projectCode) {
        ScanProjectEntity project = new ScanProjectEntity();
        project.setId(id);
        project.setProjectCode(projectCode);
        return project;
    }

    private Map<String, Object> readObject(Object value) throws Exception {
        if (value instanceof Map<?, ?> map) {
            return objectMapper.convertValue(map, new TypeReference<>() {
            });
        }
        return objectMapper.readValue(String.valueOf(value), new TypeReference<>() {
        });
    }
}
