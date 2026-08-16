package com.enterprise.ai.control.platform;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PlatformEmbedSessionServiceTest {

    @Test
    void renewsExpiredSessionWithFreshTokenForSameBoundIdentity() {
        PlatformEmbedSessionMapper mapper = mock(PlatformEmbedSessionMapper.class);
        PlatformEmbedSessionService service = new PlatformEmbedSessionService(mapper, new ObjectMapper());
        PlatformEmbedSessionEntity session = session();
        session.setExpiresAt(LocalDateTime.now().minusSeconds(1));
        when(mapper.selectOne(any())).thenReturn(session);
        PlatformEmbedTokenClaims claims = claims("agent-1", "project-1", "user-1");
        claims.setExpiresAt(Instant.now().plusSeconds(300).getEpochSecond());

        PlatformEmbedSessionEntity resolved = service.requireActiveSession("embed-1", claims);

        assertSame(session, resolved);
        assertTrue(resolved.getExpiresAt().isAfter(LocalDateTime.now()));
        verify(mapper).updateById(session);
    }

    @Test
    void neverRenewsExpiredSessionForDifferentIdentity() {
        PlatformEmbedSessionMapper mapper = mock(PlatformEmbedSessionMapper.class);
        PlatformEmbedSessionService service = new PlatformEmbedSessionService(mapper, new ObjectMapper());
        PlatformEmbedSessionEntity session = session();
        session.setExpiresAt(LocalDateTime.now().minusSeconds(1));
        when(mapper.selectOne(any())).thenReturn(session);
        PlatformEmbedTokenClaims claims = claims("agent-1", "project-1", "other-user");
        claims.setExpiresAt(Instant.now().plusSeconds(300).getEpochSecond());

        assertThrows(PlatformEmbedTokenException.class,
                () -> service.requireActiveSession("embed-1", claims));
        verify(mapper, never()).updateById(any());
    }

    @Test
    void rejectsTokenIssuedForDifferentPageInstance() {
        PlatformEmbedSessionMapper mapper = mock(PlatformEmbedSessionMapper.class);
        PlatformEmbedSessionService service =
                new PlatformEmbedSessionService(mapper, new ObjectMapper());
        PlatformEmbedSessionEntity session = session();
        when(mapper.selectOne(any())).thenReturn(session);
        PlatformEmbedTokenClaims claims =
                claims("agent-1", "project-1", "user-1");
        claims.setPageInstanceId("page-instance-2");
        claims.setExpiresAt(Instant.now().plusSeconds(300).getEpochSecond());

        assertThrows(
                PlatformEmbedTokenException.class,
                () -> service.requireActiveSession("embed-1", claims));
        verify(mapper, never()).updateById(any());
    }

    @Test
    void allowsTheSamePrincipalToRebindAnActiveSessionToTheTargetPage() {
        PlatformEmbedSessionMapper mapper = mock(PlatformEmbedSessionMapper.class);
        PlatformEmbedSessionService service = new PlatformEmbedSessionService(mapper, new ObjectMapper());
        PlatformEmbedSessionEntity session = session();
        when(mapper.selectOne(any())).thenReturn(session);
        PlatformEmbedTokenClaims targetClaims = claims("agent-1", "project-1", "user-1");
        targetClaims.setPageKey("orders.audit");
        targetClaims.setPageInstanceId("page-2");
        targetClaims.setRoute("/orders/audit");

        PlatformEmbedSessionEntity active = service.requireActiveSessionForNavigationRebind("embed-1", targetClaims);
        service.rebindBridgeAfterNavigation(
                active,
                targetClaims,
                "orders.audit",
                "page-2",
                "/orders/audit",
                List.of("orders.audit.search"),
                "1.0.0-SNAPSHOT");

        assertEquals("orders.audit", session.getPageKey());
        assertEquals("page-2", session.getPageInstanceId());
        assertEquals("/orders/audit", session.getRoute());
        verify(mapper).updateById(session);
    }

    private PlatformEmbedSessionEntity session() {
        PlatformEmbedSessionEntity session = new PlatformEmbedSessionEntity();
        session.setSessionId("embed-1");
        session.setStatus("ACTIVE");
        session.setAgentId("agent-1");
        session.setProjectCode("project-1");
        session.setExternalUserId("user-1");
        session.setPageInstanceId("page-instance-1");
        return session;
    }

    private PlatformEmbedTokenClaims claims(String agentId, String projectCode, String userId) {
        PlatformEmbedTokenClaims claims = new PlatformEmbedTokenClaims();
        claims.setAgentId(agentId);
        claims.setProjectCode(projectCode);
        claims.setExternalUserId(userId);
        claims.setPageInstanceId("page-instance-1");
        return claims;
    }
}
