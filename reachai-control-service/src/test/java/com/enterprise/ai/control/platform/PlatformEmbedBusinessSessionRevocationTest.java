package com.enterprise.ai.control.platform;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PlatformEmbedBusinessSessionRevocationTest {
    private static final String SESSION = "abcdefghijklmnopqrstuvwxyz0123456789ABCDE";
    private static final String OTHER = "abcdefghijklmnopqrstuvwxyz0123456789OTHER";

    @Test
    void embedTokenCannotOutliveTrustedHostAccessToken() {
        PlatformEmbedTokenProperties properties = new PlatformEmbedTokenProperties();
        properties.setSecret("test-signing-secret");
        properties.setDefaultTokenTtlSeconds(600);
        Clock atIssue = Clock.fixed(Instant.ofEpochSecond(1000), ZoneOffset.UTC);
        var sessions = mock(PlatformEmbedBusinessSessionRevocation.class);
        var issuer = new PlatformEmbedTokenService(new ObjectMapper(), properties, atIssue, sessions);
        var request = command("erp", SESSION);
        request.setBusinessSessionExpiresAt(1030L);
        var issued = issuer.issue(request);
        assertEquals(30, issued.expiresIn());
        assertEquals(1030, issued.expiresAt().getEpochSecond());
        var atExpiry = new PlatformEmbedTokenService(new ObjectMapper(), properties,
                Clock.fixed(Instant.ofEpochSecond(1030), ZoneOffset.UTC), sessions);
        assertThrows(PlatformEmbedTokenException.class, () -> atExpiry.verify(issued.token()));
        request.setBusinessSessionExpiresAt(1000L);
        assertThrows(PlatformEmbedTokenException.class, () -> issuer.issue(request));
        request.setBusinessSessionExpiresAt(Long.MAX_VALUE);
        assertThrows(PlatformEmbedTokenException.class, () -> issuer.issue(request));
        request.setBusinessSessionExpiresAt(3000L);
        assertEquals(600, issuer.issue(request).expiresIn(), "host expiry must not extend project policy");
    }

    @Test
    void sharedDatabaseEnforcesRevocationAcrossInstances() {
        var dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:embed_session_revoke;MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        var jdbc1 = new JdbcTemplate(dataSource);
        var jdbc2 = new JdbcTemplate(dataSource);
        jdbc1.execute("CREATE TABLE control_embed_token_revocation ("
                + "jti VARCHAR(128) PRIMARY KEY, reason VARCHAR(256))");
        var first = new PlatformEmbedBusinessSessionRevocation(jdbc1);
        var second = new PlatformEmbedBusinessSessionRevocation(jdbc2);
        second.requireActive("erp", SESSION);
        first.revoke("erp", SESSION);
        first.revoke("erp", SESSION);
        assertThrows(PlatformEmbedTokenException.class, () -> second.requireActive("erp", SESSION));
        second.requireActive("erp", OTHER);
        second.requireActive("other", SESSION);
        jdbc1.execute("DROP TABLE control_embed_token_revocation");
        assertThrows(org.springframework.dao.DataAccessException.class,
                () -> second.requireActive("erp", SESSION));
    }

    @Test
    void revokesAllTokensForOneBusinessSessionAcrossServiceInstances() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        Set<String> revoked = ConcurrentHashMap.newKeySet();
        when(jdbc.queryForObject(anyString(), eq(Integer.class), any(Object.class)))
                .thenAnswer(invocation -> revoked.contains(invocation.getArgument(2)) ? 1 : 0);
        when(jdbc.update(anyString(), any(Object.class), any(Object.class)))
                .thenAnswer(invocation -> { revoked.add(invocation.getArgument(1)); return 1; });
        var store1 = new PlatformEmbedBusinessSessionRevocation(jdbc);
        var store2 = new PlatformEmbedBusinessSessionRevocation(jdbc);
        PlatformEmbedTokenProperties properties = new PlatformEmbedTokenProperties();
        properties.setSecret("test-signing-secret");
        var issuer = new PlatformEmbedTokenService(new ObjectMapper(), properties, store1);
        var verifier = new PlatformEmbedTokenService(new ObjectMapper(), properties, store2);
        String first = issuer.issue(command("erp", SESSION)).token();
        String renewed = issuer.issue(command("erp", SESSION)).token();
        String otherSession = issuer.issue(command("erp", OTHER)).token();
        String otherProject = issuer.issue(command("other", SESSION)).token();
        assertEquals(SESSION, verifier.verify(first).getBusinessSessionId());

        store1.revoke("erp", SESSION);
        store1.revoke("erp", SESSION); // retries are idempotent
        assertThrows(PlatformEmbedTokenException.class, () -> verifier.verify(first));
        assertThrows(PlatformEmbedTokenException.class, () -> verifier.verify(renewed));
        assertThrows(PlatformEmbedTokenException.class, () -> issuer.issue(command("erp", SESSION)));
        assertEquals(OTHER, verifier.verify(otherSession).getBusinessSessionId());
        assertEquals(SESSION, verifier.verify(otherProject).getBusinessSessionId());
    }

    @Test
    void boundTokensFailClosedWhenStoreUnavailableAndMalformedIdsAreRejected() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(), eq(Integer.class), any(Object.class)))
                .thenThrow(new org.springframework.dao.DataAccessResourceFailureException("unavailable"));
        PlatformEmbedTokenProperties properties = new PlatformEmbedTokenProperties();
        properties.setSecret("test-signing-secret");
        var service = new PlatformEmbedTokenService(new ObjectMapper(), properties,
                new PlatformEmbedBusinessSessionRevocation(jdbc));
        assertThrows(org.springframework.dao.DataAccessResourceFailureException.class,
                () -> service.issue(command("erp", SESSION)));
        assertThrows(PlatformEmbedTokenException.class,
                () -> PlatformEmbedBusinessSessionRevocation.key("erp", "short"));
        assertNotEquals(PlatformEmbedBusinessSessionRevocation.key("erp", SESSION),
                PlatformEmbedBusinessSessionRevocation.key("other", SESSION));
    }

    private static PlatformEmbedTokenIssueCommand command(String project, String session) {
        return PlatformEmbedTokenIssueCommand.builder().projectCode(project).agentId("agent")
                .pageInstanceId("page").origin("http://localhost:5173").businessSessionId(session)
                .principal(new PlatformEmbedTokenIssueCommand.BusinessPrincipal("user", "user", "User", List.of(), Map.of()))
                .build();
    }
}
