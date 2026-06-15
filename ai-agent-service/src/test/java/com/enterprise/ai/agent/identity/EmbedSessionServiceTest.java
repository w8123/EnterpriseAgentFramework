package com.enterprise.ai.agent.identity;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class EmbedSessionServiceTest {

    @Test
    void createRejectsRouteThatDoesNotMatchEmbedToken() {
        EmbedSessionMapper mapper = mock(EmbedSessionMapper.class);
        EmbedSessionService service = new EmbedSessionService(mapper, new ObjectMapper());

        EmbedTokenClaims claims = new EmbedTokenClaims();
        claims.setTenantId("default");
        claims.setAppId("bzsdk");
        claims.setProjectCode("bzsdk");
        claims.setAgentId("team-agent");
        claims.setExternalUserId("ADMIN001");
        claims.setGlobalUserId("emp-001");
        claims.setPageInstanceId("page-1");
        claims.setRoute("/team-management");
        claims.setOrigin("https://biz.example");
        claims.setExpiresAt(Instant.now().plusSeconds(300).getEpochSecond());

        EmbedTokenException ex = assertThrows(EmbedTokenException.class,
                () -> service.create(claims, null, "page-1", "/other-page", List.of(), "1.0.0"));

        assertEquals("route does not match embed token", ex.getMessage());
        verify(mapper, never()).insert(any());
    }

    @Test
    void createRejectsPageKeyThatDoesNotMatchEmbedToken() {
        EmbedSessionMapper mapper = mock(EmbedSessionMapper.class);
        EmbedSessionService service = new EmbedSessionService(mapper, new ObjectMapper());

        EmbedTokenClaims claims = new EmbedTokenClaims();
        claims.setTenantId("default");
        claims.setAppId("bzsdk");
        claims.setProjectCode("bzsdk");
        claims.setAgentId("team-agent");
        claims.setExternalUserId("ADMIN001");
        claims.setGlobalUserId("emp-001");
        claims.setPageKey("teamArchive.list");
        claims.setPageInstanceId("page-1");
        claims.setRoute("/team-management");
        claims.setOrigin("https://biz.example");
        claims.setExpiresAt(Instant.now().plusSeconds(300).getEpochSecond());

        EmbedTokenException ex = assertThrows(EmbedTokenException.class,
                () -> service.create(claims, "other.page", "page-1", "/team-management", List.of(), "1.0.0"));

        assertEquals("pageKey does not match embed token", ex.getMessage());
        verify(mapper, never()).insert(any());
    }

    @Test
    void createStoresExpiryInLocalApplicationTime() {
        EmbedSessionMapper mapper = mock(EmbedSessionMapper.class);
        EmbedSessionService service = new EmbedSessionService(mapper, new ObjectMapper());

        EmbedTokenClaims claims = new EmbedTokenClaims();
        claims.setTenantId("default");
        claims.setAppId("qmssmp-teams-construction-service");
        claims.setProjectCode("qmssmp-teams-construction-service");
        claims.setAgentId("team-archive-assistant");
        claims.setExternalUserId("ztadmin");
        claims.setGlobalUserId("ztadmin");
        claims.setPageKey("teamArchive.list");
        claims.setPageInstanceId("page-1");
        claims.setRoute("/team-build/depart-management");
        claims.setOrigin("http://localhost:9200");
        claims.setExpiresAt(Instant.now().plusSeconds(1800).getEpochSecond());

        service.create(claims, "teamArchive.list", "page-1", "/team-build/depart-management", List.of("qmssmp.teamArchive.search"), "test");

        ArgumentCaptor<EmbedSessionEntity> captor = ArgumentCaptor.forClass(EmbedSessionEntity.class);
        verify(mapper).insert(captor.capture());
        assertEquals("teamArchive.list", captor.getValue().getPageKey());
        LocalDateTime expiresAt = captor.getValue().getExpiresAt();
        assertEquals(true, expiresAt.isAfter(LocalDateTime.now().plusMinutes(20)));
    }
}
