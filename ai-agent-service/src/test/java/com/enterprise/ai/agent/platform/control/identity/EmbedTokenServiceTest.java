package com.enterprise.ai.agent.platform.control.identity;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class EmbedTokenServiceTest {

    private final Clock clock = Clock.fixed(Instant.parse("2026-05-27T10:00:00Z"), ZoneOffset.UTC);
    private final EmbedTokenProperties properties = new EmbedTokenProperties();
    private final EmbedTokenService service;

    EmbedTokenServiceTest() {
        properties.setSecret("test-secret-with-enough-length-for-hmac");
        properties.setIssuer("reachai-test");
        properties.setAudience("reachai-chat-embed-test");
        properties.setDefaultTokenTtlSeconds(600);
        service = new EmbedTokenService(new ObjectMapper(), properties, clock);
    }

    @Test
    void issueTokenBindsPrincipalAgentOriginAndPageInstance() {
        EmbedTokenIssueResult issued = service.issue(EmbedTokenIssueCommand.builder()
                .tenantId("default")
                .appId("bzsdk")
                .projectCode("bzsdk")
                .agentId("team-agent")
                .pageKey("teamArchive.list")
                .pageInstanceId("page-001")
                .route("/team-management")
                .origin("http://localhost:5173")
                .principal(BusinessPrincipal.builder()
                        .tenantId("default")
                        .appId("bzsdk")
                        .externalUserId("ADMIN001")
                        .globalUserId("emp-0001")
                        .userName("System Admin")
                        .deptId("rd")
                        .roles(List.of("admin"))
                        .attributes(Map.of("orgId", "org-001"))
                        .build())
                .build());

        assertEquals(600, issued.expiresIn());

        EmbedTokenClaims claims = service.verify(issued.token());
        assertEquals("default", claims.getTenantId());
        assertEquals("bzsdk", claims.getAppId());
        assertEquals("bzsdk", claims.getProjectCode());
        assertEquals("team-agent", claims.getAgentId());
        assertEquals("ADMIN001", claims.getExternalUserId());
        assertEquals("emp-0001", claims.getGlobalUserId());
        assertEquals("teamArchive.list", claims.getPageKey());
        assertEquals("page-001", claims.getPageInstanceId());
        assertEquals("http://localhost:5173", claims.getOrigin());
        assertNotNull(claims.getJti());
        assertTrue(claims.getJti().startsWith("embed-token-"));
        assertEquals(List.of("admin"), claims.getRoles());
        assertEquals("org-001", claims.getAttributes().get("orgId"));
    }

    @Test
    void verifyRejectsExpiredToken() {
        EmbedTokenIssueResult issued = service.issue(EmbedTokenIssueCommand.builder()
                .tenantId("default")
                .appId("bzsdk")
                .projectCode("bzsdk")
                .agentId("team-agent")
                .pageInstanceId("page-001")
                .origin("http://localhost:5173")
                .ttlSeconds(1)
                .principal(BusinessPrincipal.builder()
                        .tenantId("default")
                        .appId("bzsdk")
                        .externalUserId("ADMIN001")
                        .globalUserId("emp-0001")
                        .roles(List.of("admin"))
                        .build())
                .build());

        EmbedTokenService later = new EmbedTokenService(
                new ObjectMapper(),
                properties,
                Clock.fixed(Instant.parse("2026-05-27T10:00:02Z"), ZoneOffset.UTC));

        assertThrows(EmbedTokenException.class, () -> later.verify(issued.token()));
    }

    @Test
    void verifyAcceptsTokenSignedWithConfiguredKeyId() {
        EmbedTokenProperties rotating = new EmbedTokenProperties();
        rotating.setSecret("fallback-secret-with-enough-length");
        rotating.setIssuer("reachai-test");
        rotating.setAudience("reachai-chat-embed-test");
        rotating.setActiveKeyId("v2");
        rotating.setSecrets(Map.of(
                "v1", "old-secret-with-enough-length",
                "v2", "new-secret-with-enough-length"));
        EmbedTokenService rotatingService = new EmbedTokenService(new ObjectMapper(), rotating, clock);

        EmbedTokenIssueResult issued = rotatingService.issue(command());

        assertEquals("ADMIN001", rotatingService.verify(issued.token()).getExternalUserId());
    }

    @Test
    void verifyRejectsRevokedTokenJti() {
        EmbedTokenIssueResult issued = service.issue(command());
        EmbedTokenRevocationService revocation = mock(EmbedTokenRevocationService.class);
        when(revocation.isRevoked(anyString())).thenReturn(true);
        EmbedTokenService verifying = new EmbedTokenService(new ObjectMapper(), properties, clock, revocation);

        EmbedTokenException ex = assertThrows(EmbedTokenException.class, () -> verifying.verify(issued.token()));

        assertEquals("embed token is revoked", ex.getMessage());
    }

    private EmbedTokenIssueCommand command() {
        return EmbedTokenIssueCommand.builder()
                .tenantId("default")
                .appId("bzsdk")
                .projectCode("bzsdk")
                .agentId("team-agent")
                .pageKey("teamArchive.list")
                .pageInstanceId("page-001")
                .route("/team-management")
                .origin("http://localhost:5173")
                .principal(BusinessPrincipal.builder()
                        .tenantId("default")
                        .appId("bzsdk")
                        .externalUserId("ADMIN001")
                        .globalUserId("emp-0001")
                        .roles(List.of("admin"))
                        .build())
                .build();
    }
}
