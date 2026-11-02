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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
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
        when(mapper.update(isNull(), any())).thenReturn(1);
        PlatformEmbedTokenClaims claims = claims("agent-1", "project-1", "user-1");
        claims.setExpiresAt(Instant.now().plusSeconds(300).getEpochSecond());

        PlatformEmbedSessionEntity resolved = service.requireActiveSession("embed-1", claims);

        assertSame(session, resolved);
        assertTrue(resolved.getExpiresAt().isAfter(LocalDateTime.now()));
        verify(mapper).update(isNull(), any());
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
    void sameProjectAndUserFromAnotherTenantOrAppCannotUseOrRenewSession() {
        var mapper = mock(PlatformEmbedSessionMapper.class);
        var service = new PlatformEmbedSessionService(mapper, new ObjectMapper());
        var session = session(); session.setExpiresAt(LocalDateTime.now().minusSeconds(1));
        when(mapper.selectOne(any())).thenReturn(session);
        var claims = claims("agent-1", "project-1", "user-1");
        claims.setExpiresAt(Instant.now().plusSeconds(300).getEpochSecond());
        claims.setTenantId("tenant-2");
        assertThrows(PlatformEmbedTokenException.class, () -> service.requireActiveSession("embed-1", claims));
        assertThrows(PlatformEmbedTokenException.class, () -> service.requireActiveSessionForNavigationRebind("embed-1", claims));
        claims.setTenantId("tenant-1"); claims.setAppId("other-app");
        assertThrows(PlatformEmbedTokenException.class, () -> service.requireActiveSession("embed-1", claims));
        claims.setAppId(null);
        assertThrows(PlatformEmbedTokenException.class, () -> service.requireActiveSession("embed-1", claims));
        verify(mapper, never()).updateById(any());
    }

    @Test
    void caseInsensitiveDatabaseMatchCannotResolveDifferentSessionId() {
        var mapper = mock(PlatformEmbedSessionMapper.class);
        var service = new PlatformEmbedSessionService(mapper, new ObjectMapper());
        when(mapper.selectOne(any())).thenReturn(session());
        var claims = claims("agent-1", "project-1", "user-1");
        assertThrows(PlatformEmbedTokenException.class, () -> service.requireActiveSession("EMBED-1", claims));
        assertThrows(PlatformEmbedTokenException.class, () -> service.requireActiveSessionForNavigationRebind("EMBED-1", claims));
    }

    @Test
    void differentOrMissingOriginCannotUseOrRebindSession() {
        var mapper = mock(PlatformEmbedSessionMapper.class);
        var service = new PlatformEmbedSessionService(mapper, new ObjectMapper());
        when(mapper.selectOne(any())).thenReturn(session());
        var claims = claims("agent-1", "project-1", "user-1");
        for (String origin : new String[]{"https://other.example", null, ""}) {
            claims.setOrigin(origin);
            assertThrows(PlatformEmbedTokenException.class, () -> service.requireActiveSession("embed-1", claims));
            assertThrows(PlatformEmbedTokenException.class, () -> service.requireActiveSessionForNavigationRebind("embed-1", claims));
        }
        verify(mapper, never()).updateById(any());
        verify(mapper, never()).update(any(), any());
    }

    @Test
    void sessionCannotBeReusedByAnotherBusinessLoginOrAnUnboundToken() {
        var mapper = mock(PlatformEmbedSessionMapper.class);
        var service = new PlatformEmbedSessionService(mapper, new ObjectMapper());
        var entity = session();
        entity.setBusinessSessionId("login-A");
        when(mapper.selectOne(any())).thenReturn(entity);
        var claims = claims("agent-1", "project-1", "user-1");
        claims.setBusinessSessionId("login-A");
        assertSame(entity, service.requireActiveSession("embed-1", claims));
        for (String id : new String[]{"login-B", "login-a", null}) {
            claims.setBusinessSessionId(id);
            assertThrows(PlatformEmbedTokenException.class, () -> service.requireActiveSession("embed-1", claims));
            assertThrows(PlatformEmbedTokenException.class, () -> service.requireActiveSessionForNavigationRebind("embed-1", claims));
        }
        entity.setBusinessSessionId(null);
        claims.setBusinessSessionId("login-A");
        assertThrows(PlatformEmbedTokenException.class, () -> service.requireActiveSession("embed-1", claims));
        claims.setBusinessSessionId(null);
        assertSame(entity, service.requireActiveSession("embed-1", claims));
    }

    @Test
    void businessLoginIdentifierIsNotExposedByEntitySerializationOrToString() throws Exception {
        var entity = session();
        entity.setBusinessSessionId("opaque-login-secret");
        assertFalse(new ObjectMapper().writeValueAsString(entity).contains("opaque-login-secret"));
        assertFalse(entity.toString().contains("opaque-login-secret"));
    }

    @Test
    void signedTokenForAnotherTenantCannotReuseAnExistingSession() {
        var properties = new PlatformEmbedTokenProperties(); properties.setSecret("session-test-signing-key");
        var tokens = new PlatformEmbedTokenService(new ObjectMapper(), properties);
        var mapper = mock(PlatformEmbedSessionMapper.class);
        var service = new PlatformEmbedSessionService(mapper, new ObjectMapper());
        var token = tokens.issue(PlatformEmbedTokenIssueCommand.builder()
                .tenantId("tenant-2").appId("project-1").projectCode("project-1").agentId("agent-1")
                .pageInstanceId("page-instance-1").origin("http://localhost")
                .principal(new PlatformEmbedTokenIssueCommand.BusinessPrincipal("user-1", "user-1", "user", List.of(), java.util.Map.of()))
                .build());
        when(mapper.selectOne(any())).thenReturn(session());
        assertThrows(PlatformEmbedTokenException.class,
                () -> service.requireActiveSession("embed-1", tokens.verify(token.token())));
        verify(mapper, never()).updateById(any());
    }

    @Test
    void allowsTheSamePrincipalToRebindAnActiveSessionToTheTargetPage() {
        PlatformEmbedSessionMapper mapper = mock(PlatformEmbedSessionMapper.class);
        PlatformEmbedSessionService service = new PlatformEmbedSessionService(mapper, new ObjectMapper());
        PlatformEmbedSessionEntity session = session();
        when(mapper.selectOne(any())).thenReturn(session);
        when(mapper.updateById(any(PlatformEmbedSessionEntity.class))).thenReturn(1);
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
        session.setId(1L);
        session.setSessionId("embed-1");
        session.setStatus("ACTIVE");
        session.setTenantId("tenant-1"); session.setAppId("project-1");
        session.setAgentId("agent-1");
        session.setProjectCode("project-1");
        session.setExternalUserId("user-1");
        session.setPageInstanceId("page-instance-1");
        session.setOrigin("http://localhost");
        return session;
    }

    private PlatformEmbedTokenClaims claims(String agentId, String projectCode, String userId) {
        PlatformEmbedTokenClaims claims = new PlatformEmbedTokenClaims();
        claims.setTenantId("tenant-1"); claims.setAppId("project-1");
        claims.setAgentId(agentId);
        claims.setProjectCode(projectCode);
        claims.setExternalUserId(userId);
        claims.setPageInstanceId("page-instance-1");
        claims.setOrigin("http://localhost");
        return claims;
    }
}
