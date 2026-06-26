package com.enterprise.ai.agent.platform.control.context.memory;

import com.enterprise.ai.agent.platform.control.identity.EmbedSessionEntity;
import com.enterprise.ai.agent.platform.control.identity.EmbedTokenClaims;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RuntimeUserIdentityResolverTest {

    private ContextMemoryCandidateService candidateService;
    private RuntimeUserIdentityResolver resolver;

    @BeforeEach
    void setUp() {
        candidateService = mock(ContextMemoryCandidateService.class);
        resolver = new RuntimeUserIdentityResolver(candidateService);
        when(candidateService.resolveUserId(any(), any(), any())).thenAnswer(invocation ->
                firstText(invocation.getArgument(0), invocation.getArgument(1), invocation.getArgument(2)));
    }

    @Test
    void prefersGlobalUserIdOverExternalUserId() {
        RuntimeUserIdentity identity = resolver.resolve(session("default", "global-a", "external-a"), claims("global-a", "external-a"));

        assertEquals("default", identity.tenantId());
        assertEquals("global-a", identity.userId());
        assertEquals("global-a", identity.globalUserId());
        assertEquals("external-a", identity.externalUserId());
    }

    @Test
    void fallsBackToExternalUserIdWhenGlobalMissing() {
        RuntimeUserIdentity identity = resolver.resolve(session("default", null, "external-a"), claims(null, "external-a"));

        assertEquals("external-a", identity.userId());
    }

    @Test
    void rejectsWhenUserIdCannotBeResolved() {
        assertThrows(IllegalArgumentException.class,
                () -> resolver.resolve(session("default", null, null), claims(null, null)));
    }

    @Test
    void doesNotUsePlatformUserIdFallback() {
        when(candidateService.resolveUserId("global-a", "external-a", null)).thenReturn("global-a");

        RuntimeUserIdentity identity = resolver.resolve(session("default", "global-a", "external-a"), claims("global-a", "external-a"));

        assertEquals("global-a", identity.userId());
    }

    private EmbedSessionEntity session(String tenantId, String globalUserId, String externalUserId) {
        EmbedSessionEntity session = new EmbedSessionEntity();
        session.setSessionId("sess-1");
        session.setTenantId(tenantId);
        session.setProjectCode("demo");
        session.setGlobalUserId(globalUserId);
        session.setExternalUserId(externalUserId);
        return session;
    }

    private EmbedTokenClaims claims(String globalUserId, String externalUserId) {
        EmbedTokenClaims claims = new EmbedTokenClaims();
        claims.setTenantId("default");
        claims.setGlobalUserId(globalUserId);
        claims.setExternalUserId(externalUserId);
        return claims;
    }

    private static String firstText(String globalUserId, String externalUserId, String userId) {
        if (globalUserId != null && !globalUserId.isBlank()) {
            return globalUserId.trim();
        }
        if (externalUserId != null && !externalUserId.isBlank()) {
            return externalUserId.trim();
        }
        if (userId != null && !userId.isBlank()) {
            return userId.trim();
        }
        return null;
    }
}
