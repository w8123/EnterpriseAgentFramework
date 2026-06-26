package com.enterprise.ai.agent.platform.control.controller;

import com.enterprise.ai.agent.platform.control.context.memory.*;
import com.enterprise.ai.agent.platform.control.controller.EmbedMemoryCandidateController;
import com.enterprise.ai.agent.platform.control.identity.EmbedSessionEntity;
import com.enterprise.ai.agent.platform.control.identity.EmbedSessionService;
import com.enterprise.ai.agent.platform.control.identity.EmbedTokenClaims;
import com.enterprise.ai.agent.platform.control.identity.EmbedTokenService;
import com.enterprise.ai.common.dto.ApiResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class EmbedMemoryCandidateControllerTest {

    private EmbedTokenService embedTokenService;
    private EmbedSessionService embedSessionService;
    private RuntimeUserIdentityResolver runtimeUserIdentityResolver;
    private ContextMemoryCandidateService candidateService;
    private EmbedMemoryCandidateController controller;

    @BeforeEach
    void setUp() {
        embedTokenService = mock(EmbedTokenService.class);
        embedSessionService = mock(EmbedSessionService.class);
        runtimeUserIdentityResolver = mock(RuntimeUserIdentityResolver.class);
        candidateService = mock(ContextMemoryCandidateService.class);
        controller = new EmbedMemoryCandidateController(
                embedTokenService,
                embedSessionService,
                runtimeUserIdentityResolver,
                candidateService);

        when(embedTokenService.verify("token-1")).thenReturn(claims());
        when(embedSessionService.requireActiveSession(eq("sess-1"), any())).thenReturn(session());
        when(runtimeUserIdentityResolver.resolve(any(), any())).thenReturn(identity("global-a"));
    }

    @Test
    void listCandidatesInjectsRuntimeIdentityFromSession() {
        when(candidateService.listCandidates(any())).thenReturn(List.of(
                ContextMemoryCandidateResponse.builder().id(1L).userId("global-a").build()));

        ApiResult<List<ContextMemoryCandidateResponse>> result = controller.listCandidates(
                "sess-1", "Bearer token-1", "PENDING", false, 20);

        assertEquals(200, result.getCode());
        verify(embedSessionService).requireActiveSession(eq("sess-1"), any());
        var captor = org.mockito.ArgumentCaptor.forClass(ContextMemoryCandidateQueryRequest.class);
        verify(candidateService).listCandidates(captor.capture());
        assertEquals("default", captor.getValue().getTenantId());
        assertEquals("global-a", captor.getValue().getUserId());
        assertEquals("demo", captor.getValue().getProjectCode());
        assertEquals("PENDING", captor.getValue().getStatus());
        assertEquals(20, captor.getValue().getLimit());
    }

    @Test
    void approveCandidateIgnoresClientIdentityFieldsAndUsesRuntimeUser() {
        when(candidateService.approveCandidate(eq(9L), any()))
                .thenReturn(ContextMemoryCandidateResponse.builder().id(9L).status("APPROVED").build());

        EmbedMemoryCandidateReviewRequest body = new EmbedMemoryCandidateReviewRequest();
        body.setReviewReason("confirmed");
        body.setConfidence(new BigDecimal("0.9000"));
        body.setTrustLevel("HIGH");

        ApiResult<ContextMemoryCandidateResponse> result = controller.approveCandidate(
                "sess-1", 9L, "Bearer token-1", body);

        assertEquals(200, result.getCode());
        var captor = org.mockito.ArgumentCaptor.forClass(ContextMemoryCandidateReviewRequest.class);
        verify(candidateService).approveCandidate(eq(9L), captor.capture());
        assertEquals("default", captor.getValue().getTenantId());
        assertEquals("global-a", captor.getValue().getUserId());
        assertEquals("global-a", captor.getValue().getReviewedBy());
        assertEquals("confirmed", captor.getValue().getReviewReason());
        assertEquals("HIGH", captor.getValue().getTrustLevel());
    }

    @Test
    void rejectCandidateUsesRuntimeReviewedBy() {
        when(candidateService.rejectCandidate(eq(10L), any()))
                .thenReturn(ContextMemoryCandidateResponse.builder().id(10L).status("REJECTED").build());

        EmbedMemoryCandidateReviewRequest body = new EmbedMemoryCandidateReviewRequest();
        body.setReviewReason("not needed");

        controller.rejectCandidate("sess-1", 10L, "Bearer token-1", body);

        var captor = org.mockito.ArgumentCaptor.forClass(ContextMemoryCandidateReviewRequest.class);
        verify(candidateService).rejectCandidate(eq(10L), captor.capture());
        assertEquals("global-a", captor.getValue().getReviewedBy());
    }

    @Test
    void deleteCandidateUsesRuntimeIdentity() {
        when(candidateService.deleteCandidate(eq(11L), any()))
                .thenReturn(ContextMemoryCandidateResponse.builder().id(11L).status("DELETED").build());

        controller.deleteCandidate("sess-1", 11L, "Bearer token-1");

        var captor = org.mockito.ArgumentCaptor.forClass(ContextMemoryCandidateQueryRequest.class);
        verify(candidateService).deleteCandidate(eq(11L), captor.capture());
        assertEquals("global-a", captor.getValue().getUserId());
    }

    @Test
    void listCandidatesFailsWhenIdentityCannotBeResolved() {
        when(runtimeUserIdentityResolver.resolve(any(), any()))
                .thenThrow(new IllegalArgumentException("runtime userId is required"));

        ApiResult<List<ContextMemoryCandidateResponse>> result = controller.listCandidates(
                "sess-1", "Bearer token-1", null, false, null);

        assertNotEquals(200, result.getCode());
        verify(candidateService, never()).listCandidates(any());
    }

    private RuntimeUserIdentity identity(String userId) {
        return new RuntimeUserIdentity("default", userId, userId, "external-a", "demo");
    }

    private EmbedSessionEntity session() {
        EmbedSessionEntity session = new EmbedSessionEntity();
        session.setSessionId("sess-1");
        session.setTenantId("default");
        session.setProjectCode("demo");
        session.setGlobalUserId("global-a");
        session.setExternalUserId("external-a");
        return session;
    }

    private EmbedTokenClaims claims() {
        EmbedTokenClaims claims = new EmbedTokenClaims();
        claims.setTenantId("default");
        claims.setGlobalUserId("global-a");
        claims.setExternalUserId("external-a");
        claims.setProjectCode("demo");
        return claims;
    }
}
