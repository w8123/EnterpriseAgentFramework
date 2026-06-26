package com.enterprise.ai.agent.platform.control.controller;

import com.enterprise.ai.agent.platform.control.controller.ContextMemoryCandidateController;
import com.enterprise.ai.agent.platform.control.context.memory.*;
import com.enterprise.ai.agent.platform.control.context.MemoryLane;
import com.enterprise.ai.agent.platform.control.context.ContextRuntimeUserAccessService;
import com.enterprise.ai.agent.platform.control.auth.PlatformAuthContext;
import com.enterprise.ai.agent.platform.control.auth.PlatformPrincipal;
import com.enterprise.ai.common.dto.ApiResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class ContextMemoryCandidateControllerTest {

    private ContextMemoryCandidateService candidateService;
    private ContextRuntimeUserAccessService runtimeUserAccessService;
    private ContextMemoryCandidateController controller;

    @BeforeEach
    void setUp() {
        candidateService = mock(ContextMemoryCandidateService.class);
        runtimeUserAccessService = mock(ContextRuntimeUserAccessService.class);
        controller = new ContextMemoryCandidateController(candidateService, runtimeUserAccessService);
        PlatformAuthContext.set(new PlatformPrincipal(100L, "alice", "Alice", Set.of("USER"), Set.of()));
        when(candidateService.resolveUserId(any(), any(), any())).thenAnswer(inv -> {
            Object userId = inv.getArgument(2);
            return userId == null ? null : String.valueOf(userId);
        });
    }

    @AfterEach
    void tearDown() {
        PlatformAuthContext.clear();
    }

    @Test
    void listCandidatesBindsCurrentPlatformUserId() {
        when(candidateService.listCandidates(any())).thenReturn(List.of());

        ApiResult<List<ContextMemoryCandidateResponse>> result = controller.listCandidates(query("100"));

        assertEquals(200, result.getCode());
        var captor = org.mockito.ArgumentCaptor.forClass(ContextMemoryCandidateQueryRequest.class);
        verify(candidateService).listCandidates(captor.capture());
        assertEquals("100", captor.getValue().getUserId());
    }

    @Test
    void listCandidatesRejectsImpersonatedUserId() {
        ApiResult<List<ContextMemoryCandidateResponse>> result = controller.listCandidates(query("999"));

        assertNotEquals(200, result.getCode());
        verify(candidateService, never()).listCandidates(any());
    }

    @Test
    void listRuntimeUserCandidatesAllowsMappedReviewer() {
        PlatformAuthContext.set(new PlatformPrincipal(100L, "alice", "Alice",
                Set.of("CONTEXT_OPERATOR"), Set.of("context:runtime-user:review")));
        when(runtimeUserAccessService.canReviewRuntimeUser(any(), eq("default"), eq("runtime-user-a"), eq("demo-project"), isNull()))
                .thenReturn(true);
        when(candidateService.listCandidates(any())).thenReturn(List.of());

        ContextMemoryCandidateQueryRequest query = query("runtime-user-a");
        query.setProjectCode("demo-project");
        ApiResult<List<ContextMemoryCandidateResponse>> result = controller.listCandidates(query);

        assertEquals(200, result.getCode());
        var captor = org.mockito.ArgumentCaptor.forClass(ContextMemoryCandidateQueryRequest.class);
        verify(candidateService).listCandidates(captor.capture());
        assertEquals("runtime-user-a", captor.getValue().getUserId());
        verify(runtimeUserAccessService).canReviewRuntimeUser(any(), eq("default"), eq("runtime-user-a"),
                eq("demo-project"), isNull());
    }

    @Test
    void listRuntimeUserCandidatesRejectsReviewerWithoutMapping() {
        PlatformAuthContext.set(new PlatformPrincipal(100L, "alice", "Alice",
                Set.of("CONTEXT_OPERATOR"), Set.of("context:runtime-user:review")));
        when(runtimeUserAccessService.canReviewRuntimeUser(any(), eq("default"), eq("runtime-user-a"), isNull(), isNull()))
                .thenReturn(false);

        ApiResult<List<ContextMemoryCandidateResponse>> result = controller.listCandidates(query("runtime-user-a"));

        assertEquals(403, result.getCode());
        verify(candidateService, never()).listCandidates(any());
    }

    @Test
    void listProjectDevCandidatesDoesNotRequireRuntimeUserId() {
        when(candidateService.listCandidates(any())).thenReturn(List.of());

        ContextMemoryCandidateQueryRequest query = projectDevQuery();
        ApiResult<List<ContextMemoryCandidateResponse>> result = controller.listCandidates(query);

        assertEquals(200, result.getCode());
        var captor = org.mockito.ArgumentCaptor.forClass(ContextMemoryCandidateQueryRequest.class);
        verify(candidateService).listCandidates(captor.capture());
        assertEquals(MemoryLane.PROJECT_DEV.name(), captor.getValue().getMemoryLane());
        assertEquals("demo-project", captor.getValue().getProjectCode());
        assertNull(captor.getValue().getUserId());
    }

    @Test
    void approveCandidateDefaultsReviewedByToCurrentPrincipal() {
        when(candidateService.approveCandidate(eq(5L), any()))
                .thenReturn(ContextMemoryCandidateResponse.builder().id(5L).status("APPROVED").build());

        ApiResult<ContextMemoryCandidateResponse> result = controller.approveCandidate(5L, review("100", null));

        assertEquals(200, result.getCode());
        var captor = org.mockito.ArgumentCaptor.forClass(ContextMemoryCandidateReviewRequest.class);
        verify(candidateService).approveCandidate(eq(5L), captor.capture());
        assertEquals("100", captor.getValue().getUserId());
        assertEquals("100", captor.getValue().getReviewedBy());
    }

    @Test
    void approveCandidateRejectsForeignReviewer() {
        ApiResult<ContextMemoryCandidateResponse> result = controller.approveCandidate(5L, review("100", "999"));

        assertNotEquals(200, result.getCode());
        verify(candidateService, never()).approveCandidate(any(), any());
    }

    @Test
    void approveRuntimeUserCandidateAllowsMappedReviewerAndKeepsReviewerAuditActor() {
        PlatformAuthContext.set(new PlatformPrincipal(100L, "alice", "Alice",
                Set.of("CONTEXT_OPERATOR"), Set.of("context:runtime-user:review")));
        when(runtimeUserAccessService.canReviewRuntimeUser(any(), eq("default"), eq("runtime-user-a"), isNull(), isNull()))
                .thenReturn(true);
        when(candidateService.approveCandidate(eq(5L), any()))
                .thenReturn(ContextMemoryCandidateResponse.builder().id(5L).status("APPROVED").build());

        ApiResult<ContextMemoryCandidateResponse> result = controller.approveCandidate(5L, review("runtime-user-a", null));

        assertEquals(200, result.getCode());
        var captor = org.mockito.ArgumentCaptor.forClass(ContextMemoryCandidateReviewRequest.class);
        verify(candidateService).approveCandidate(eq(5L), captor.capture());
        assertEquals("runtime-user-a", captor.getValue().getUserId());
        assertEquals("100", captor.getValue().getReviewedBy());
    }

    @Test
    void approveProjectDevCandidateDefaultsReviewerWithoutRuntimeUserId() {
        when(candidateService.approveCandidate(eq(6L), any()))
                .thenReturn(ContextMemoryCandidateResponse.builder().id(6L).status("APPROVED").build());

        ContextMemoryCandidateReviewRequest request = projectDevReview();
        ApiResult<ContextMemoryCandidateResponse> result = controller.approveCandidate(6L, request);

        assertEquals(200, result.getCode());
        var captor = org.mockito.ArgumentCaptor.forClass(ContextMemoryCandidateReviewRequest.class);
        verify(candidateService).approveCandidate(eq(6L), captor.capture());
        assertEquals(MemoryLane.PROJECT_DEV.name(), captor.getValue().getMemoryLane());
        assertEquals("demo-project", captor.getValue().getProjectCode());
        assertNull(captor.getValue().getUserId());
        assertEquals("100", captor.getValue().getReviewedBy());
    }

    @Test
    void updateProjectDevCandidateDefaultsUpdatedByToCurrentPrincipal() {
        when(candidateService.updateCandidate(eq(6L), any()))
                .thenReturn(ContextMemoryCandidateResponse.builder().id(6L).status("PENDING").build());

        ContextMemoryCandidateUpdateRequest request = projectDevUpdate();
        ApiResult<ContextMemoryCandidateResponse> result = controller.updateCandidate(6L, request);

        assertEquals(200, result.getCode());
        var captor = org.mockito.ArgumentCaptor.forClass(ContextMemoryCandidateUpdateRequest.class);
        verify(candidateService).updateCandidate(eq(6L), captor.capture());
        assertEquals(MemoryLane.PROJECT_DEV.name(), captor.getValue().getMemoryLane());
        assertEquals("demo-project", captor.getValue().getProjectCode());
        assertNull(captor.getValue().getUserId());
        assertEquals("100", captor.getValue().getUpdatedBy());
    }

    @Test
    void updateProjectDevCandidateRejectsForeignUpdatedBy() {
        ContextMemoryCandidateUpdateRequest request = projectDevUpdate();
        request.setUpdatedBy("999");

        ApiResult<ContextMemoryCandidateResponse> result = controller.updateCandidate(6L, request);

        assertNotEquals(200, result.getCode());
        verify(candidateService, never()).updateCandidate(any(), any());
    }

    @Test
    void approveCandidateBatchDefaultsReviewerAndKeepsProjectDevScope() {
        when(candidateService.approveCandidates(any()))
                .thenReturn(List.of(
                        ContextMemoryCandidateResponse.builder().id(6L).status("APPROVED").build(),
                        ContextMemoryCandidateResponse.builder().id(7L).status("APPROVED").build()));

        ContextMemoryCandidateBatchReviewRequest request = projectDevBatchReview();
        ApiResult<List<ContextMemoryCandidateResponse>> result = controller.approveCandidateBatch(request);

        assertEquals(200, result.getCode());
        var captor = org.mockito.ArgumentCaptor.forClass(ContextMemoryCandidateBatchReviewRequest.class);
        verify(candidateService).approveCandidates(captor.capture());
        assertEquals(List.of(6L, 7L), captor.getValue().getCandidateIds());
        assertEquals(MemoryLane.PROJECT_DEV.name(), captor.getValue().getMemoryLane());
        assertEquals("demo-project", captor.getValue().getProjectCode());
        assertNull(captor.getValue().getUserId());
        assertEquals("100", captor.getValue().getReviewedBy());
    }

    @Test
    void rejectCandidateBatchRejectsForeignReviewer() {
        ContextMemoryCandidateBatchReviewRequest request = projectDevBatchReview();
        request.setReviewedBy("999");

        ApiResult<List<ContextMemoryCandidateResponse>> result = controller.rejectCandidateBatch(request);

        assertNotEquals(200, result.getCode());
        verify(candidateService, never()).rejectCandidates(any());
    }

    @Test
    void deleteProjectDevCandidateBindsCurrentPrincipalForAudit() {
        when(candidateService.deleteCandidate(eq(6L), any()))
                .thenReturn(ContextMemoryCandidateResponse.builder().id(6L).status("DELETED").build());

        ContextMemoryCandidateQueryRequest query = projectDevQuery();
        ApiResult<ContextMemoryCandidateResponse> result = controller.deleteCandidate(6L, query);

        assertEquals(200, result.getCode());
        var captor = org.mockito.ArgumentCaptor.forClass(ContextMemoryCandidateQueryRequest.class);
        verify(candidateService).deleteCandidate(eq(6L), captor.capture());
        assertEquals(MemoryLane.PROJECT_DEV.name(), captor.getValue().getMemoryLane());
        assertEquals("demo-project", captor.getValue().getProjectCode());
        assertEquals("100", captor.getValue().getUserId());
    }

    private ContextMemoryCandidateQueryRequest query(String userId) {
        ContextMemoryCandidateQueryRequest query = new ContextMemoryCandidateQueryRequest();
        query.setTenantId("default");
        query.setUserId(userId);
        return query;
    }

    private ContextMemoryCandidateQueryRequest projectDevQuery() {
        ContextMemoryCandidateQueryRequest query = new ContextMemoryCandidateQueryRequest();
        query.setTenantId("default");
        query.setProjectCode("demo-project");
        query.setMemoryLane(MemoryLane.PROJECT_DEV.name());
        return query;
    }

    private ContextMemoryCandidateReviewRequest review(String userId, String reviewedBy) {
        ContextMemoryCandidateReviewRequest request = new ContextMemoryCandidateReviewRequest();
        request.setTenantId("default");
        request.setUserId(userId);
        request.setReviewedBy(reviewedBy);
        request.setReviewReason("confirmed");
        return request;
    }

    private ContextMemoryCandidateReviewRequest projectDevReview() {
        ContextMemoryCandidateReviewRequest request = new ContextMemoryCandidateReviewRequest();
        request.setTenantId("default");
        request.setProjectCode("demo-project");
        request.setMemoryLane(MemoryLane.PROJECT_DEV.name());
        request.setReviewReason("confirmed");
        return request;
    }

    private ContextMemoryCandidateBatchReviewRequest projectDevBatchReview() {
        ContextMemoryCandidateBatchReviewRequest request = new ContextMemoryCandidateBatchReviewRequest();
        request.setTenantId("default");
        request.setProjectCode("demo-project");
        request.setMemoryLane(MemoryLane.PROJECT_DEV.name());
        request.setReviewReason("confirmed");
        request.setCandidateIds(List.of(6L, 7L));
        return request;
    }

    private ContextMemoryCandidateUpdateRequest projectDevUpdate() {
        ContextMemoryCandidateUpdateRequest request = new ContextMemoryCandidateUpdateRequest();
        request.setTenantId("default");
        request.setProjectCode("demo-project");
        request.setMemoryLane(MemoryLane.PROJECT_DEV.name());
        request.setTitle("edited candidate");
        request.setContent("edited content");
        request.setUpdateReason("confirmed");
        return request;
    }
}
