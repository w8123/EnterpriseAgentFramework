package com.enterprise.ai.agent.platform.control.identity;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class EmbedSessionServiceTest {

    private EmbedSessionMapper mapper;
    private EmbedSessionService service;

    @BeforeEach
    void setUp() {
        mapper = mock(EmbedSessionMapper.class);
        service = new EmbedSessionService(mapper, new ObjectMapper());
    }

    @Test
    void requireActiveSessionAcceptsMatchingIdentityFields() {
        when(mapper.selectOne(any())).thenReturn(activeSession());

        EmbedSessionEntity result = service.requireActiveSession("sess-1", matchingClaims());

        assertEquals("sess-1", result.getSessionId());
        assertEquals("global-a", result.getGlobalUserId());
    }

    @Test
    void requireActiveSessionRejectsTenantIdMismatch() {
        when(mapper.selectOne(any())).thenReturn(activeSession());
        EmbedTokenClaims claims = matchingClaims();
        claims.setTenantId("other-tenant");

        EmbedTokenException ex = assertThrows(EmbedTokenException.class,
                () -> service.requireActiveSession("sess-1", claims));

        assertTrue(ex.getMessage().contains("tenantId"));
    }

    @Test
    void requireActiveSessionRejectsAppIdMismatch() {
        when(mapper.selectOne(any())).thenReturn(activeSession());
        EmbedTokenClaims claims = matchingClaims();
        claims.setAppId("other-app");

        EmbedTokenException ex = assertThrows(EmbedTokenException.class,
                () -> service.requireActiveSession("sess-1", claims));

        assertTrue(ex.getMessage().contains("appId"));
    }

    @Test
    void requireActiveSessionRejectsGlobalUserIdMismatch() {
        when(mapper.selectOne(any())).thenReturn(activeSession());
        EmbedTokenClaims claims = matchingClaims();
        claims.setGlobalUserId("other-global");

        EmbedTokenException ex = assertThrows(EmbedTokenException.class,
                () -> service.requireActiveSession("sess-1", claims));

        assertTrue(ex.getMessage().contains("globalUserId"));
    }

    @Test
    void requireActiveSessionRejectsExternalUserIdMismatch() {
        when(mapper.selectOne(any())).thenReturn(activeSession());
        EmbedTokenClaims claims = matchingClaims();
        claims.setExternalUserId("other-external");

        EmbedTokenException ex = assertThrows(EmbedTokenException.class,
                () -> service.requireActiveSession("sess-1", claims));

        assertTrue(ex.getMessage().contains("externalUserId"));
    }

    @Test
    void requireActiveSessionRejectsProjectCodeMismatch() {
        when(mapper.selectOne(any())).thenReturn(activeSession());
        EmbedTokenClaims claims = matchingClaims();
        claims.setProjectCode("other-project");

        EmbedTokenException ex = assertThrows(EmbedTokenException.class,
                () -> service.requireActiveSession("sess-1", claims));

        assertTrue(ex.getMessage().contains("projectCode"));
    }

    @Test
    void requireActiveSessionRejectsAgentIdMismatch() {
        when(mapper.selectOne(any())).thenReturn(activeSession());
        EmbedTokenClaims claims = matchingClaims();
        claims.setAgentId("other-agent");

        EmbedTokenException ex = assertThrows(EmbedTokenException.class,
                () -> service.requireActiveSession("sess-1", claims));

        assertTrue(ex.getMessage().contains("agentId"));
    }

    @Test
    void requireActiveSessionRejectsPageInstanceIdMismatch() {
        when(mapper.selectOne(any())).thenReturn(activeSession());
        EmbedTokenClaims claims = matchingClaims();
        claims.setPageInstanceId("other-page");

        EmbedTokenException ex = assertThrows(EmbedTokenException.class,
                () -> service.requireActiveSession("sess-1", claims));

        assertTrue(ex.getMessage().contains("pageInstanceId"));
    }

    @Test
    void requireActiveSessionRejectsPageKeyMismatch() {
        when(mapper.selectOne(any())).thenReturn(activeSession());
        EmbedTokenClaims claims = matchingClaims();
        claims.setPageKey("other.page");

        EmbedTokenException ex = assertThrows(EmbedTokenException.class,
                () -> service.requireActiveSession("sess-1", claims));

        assertTrue(ex.getMessage().contains("pageKey"));
    }

    @Test
    void requireActiveSessionRejectsExpiredSession() {
        EmbedSessionEntity expired = activeSession();
        expired.setExpiresAt(LocalDateTime.now().minusMinutes(1));
        when(mapper.selectOne(any())).thenReturn(expired);

        EmbedTokenException ex = assertThrows(EmbedTokenException.class,
                () -> service.requireActiveSession("sess-1", matchingClaims()));

        assertEquals("embed chat session is expired", ex.getMessage());
    }

    @Test
    void requireActiveSessionRejectsMissingSession() {
        when(mapper.selectOne(any())).thenReturn(null);

        EmbedTokenException ex = assertThrows(EmbedTokenException.class,
                () -> service.requireActiveSession("sess-missing", matchingClaims()));

        assertEquals("embed chat session not found", ex.getMessage());
    }

    @Test
    void requireActiveSessionRejectsNullClaims() {
        when(mapper.selectOne(any())).thenReturn(activeSession());

        EmbedTokenException ex = assertThrows(EmbedTokenException.class,
                () -> service.requireActiveSession("sess-1", null));

        assertEquals("embed token claims are required", ex.getMessage());
    }

    @Test
    void createRejectsRouteThatDoesNotMatchEmbedToken() {
        EmbedTokenClaims claims = baseClaims();
        claims.setRoute("/team-management");

        EmbedTokenException ex = assertThrows(EmbedTokenException.class,
                () -> service.create(claims, null, "page-1", "/other-page", List.of(), "1.0.0"));

        assertEquals("route does not match embed token", ex.getMessage());
        verify(mapper, never()).insert(any());
    }

    @Test
    void createRejectsPageKeyThatDoesNotMatchEmbedToken() {
        EmbedTokenClaims claims = baseClaims();
        claims.setPageKey("teamArchive.list");

        EmbedTokenException ex = assertThrows(EmbedTokenException.class,
                () -> service.create(claims, "other.page", "page-1", "/team-management", List.of(), "1.0.0"));

        assertEquals("pageKey does not match embed token", ex.getMessage());
        verify(mapper, never()).insert(any());
    }

    @Test
    void createStoresExpiryInLocalApplicationTime() {
        EmbedTokenClaims claims = baseClaims();
        claims.setAppId("qmssmp-teams-construction-service");
        claims.setProjectCode("qmssmp-teams-construction-service");
        claims.setAgentId("team-archive-assistant");
        claims.setExternalUserId("ztadmin");
        claims.setGlobalUserId("ztadmin");
        claims.setPageKey("teamArchive.list");
        claims.setRoute("/team-build/depart-management");
        claims.setOrigin("http://localhost:9200");

        service.create(claims, "teamArchive.list", "page-1", "/team-build/depart-management", List.of("qmssmp.teamArchive.search"), "test");

        ArgumentCaptor<EmbedSessionEntity> captor = ArgumentCaptor.forClass(EmbedSessionEntity.class);
        verify(mapper).insert(captor.capture());
        assertEquals("teamArchive.list", captor.getValue().getPageKey());
        LocalDateTime expiresAt = captor.getValue().getExpiresAt();
        assertTrue(expiresAt.isAfter(LocalDateTime.now().plusMinutes(20)));
    }

    private EmbedSessionEntity activeSession() {
        EmbedSessionEntity entity = new EmbedSessionEntity();
        entity.setSessionId("sess-1");
        entity.setStatus("ACTIVE");
        entity.setTenantId("default");
        entity.setAppId("demo");
        entity.setProjectCode("demo");
        entity.setAgentId("agent-1");
        entity.setExternalUserId("external-a");
        entity.setGlobalUserId("global-a");
        entity.setPageInstanceId("page-1");
        entity.setPageKey("orders.list");
        entity.setRoute("/orders");
        entity.setOrigin("https://biz.example");
        entity.setExpiresAt(LocalDateTime.now().plusHours(1));
        return entity;
    }

    private EmbedTokenClaims matchingClaims() {
        EmbedTokenClaims claims = baseClaims();
        claims.setPageKey("orders.list");
        claims.setRoute("/orders");
        claims.setOrigin("https://biz.example");
        return claims;
    }

    private EmbedTokenClaims baseClaims() {
        EmbedTokenClaims claims = new EmbedTokenClaims();
        claims.setTenantId("default");
        claims.setAppId("demo");
        claims.setProjectCode("demo");
        claims.setAgentId("agent-1");
        claims.setExternalUserId("external-a");
        claims.setGlobalUserId("global-a");
        claims.setPageInstanceId("page-1");
        claims.setExpiresAt(Instant.now().plusSeconds(1800).getEpochSecond());
        return claims;
    }
}
