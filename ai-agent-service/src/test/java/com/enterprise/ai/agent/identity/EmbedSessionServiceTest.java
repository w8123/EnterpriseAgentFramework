package com.enterprise.ai.agent.identity;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
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
                () -> service.create(claims, "page-1", "/other-page", List.of(), "1.0.0"));

        assertEquals("route does not match embed token", ex.getMessage());
        verify(mapper, never()).insert(any());
    }
}
