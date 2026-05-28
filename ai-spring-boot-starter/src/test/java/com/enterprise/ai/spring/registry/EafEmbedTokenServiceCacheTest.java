package com.enterprise.ai.spring.registry;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EafEmbedTokenServiceCacheTest {

    @Test
    void reusesUnexpiredEmbedTokenOnlyForSameUserAgentPageAndOrigin() {
        EafRegistryProperties properties = properties();
        EafCurrentUserProvider userProvider = () -> user("ADMIN001");
        EafRegistryClient registryClient = mock(EafRegistryClient.class);
        when(registryClient.exchangeEmbedToken(anyMap()))
                .thenReturn(new EafEmbedTokenResponse("token-1", 600, Map.of()))
                .thenReturn(new EafEmbedTokenResponse("token-2", 600, Map.of()));
        EafEmbedTokenService service = new EafEmbedTokenService(
                properties,
                userProvider,
                registryClient,
                Clock.fixed(Instant.parse("2026-05-27T00:00:00Z"), ZoneOffset.UTC));

        EafEmbedTokenRequest request = new EafEmbedTokenRequest("agent-a", "page-1", "/team", "https://biz.example.com");

        assertEquals("token-1", service.issueToken(request).token());
        assertEquals("token-1", service.issueToken(request).token());
        assertEquals("token-2", service.issueToken(new EafEmbedTokenRequest(
                "agent-a", "page-2", "/team", "https://biz.example.com")).token());
        verify(registryClient, times(2)).exchangeEmbedToken(anyMap());
    }

    @Test
    void refreshesTokenWhenCacheIsInsideExpirySkew() {
        EafRegistryProperties properties = properties();
        EafCurrentUserProvider userProvider = () -> user("ADMIN001");
        EafRegistryClient registryClient = mock(EafRegistryClient.class);
        when(registryClient.exchangeEmbedToken(anyMap()))
                .thenReturn(new EafEmbedTokenResponse("token-1", 20, Map.of()))
                .thenReturn(new EafEmbedTokenResponse("token-2", 600, Map.of()));
        AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-05-27T00:00:00Z"));
        EafEmbedTokenService service = new EafEmbedTokenService(
                properties,
                userProvider,
                registryClient,
                Clock.fixed(now.get(), ZoneOffset.UTC));

        EafEmbedTokenRequest request = new EafEmbedTokenRequest("agent-a", "page-1", "/team", "https://biz.example.com");

        assertEquals("token-1", service.issueToken(request).token());
        assertEquals("token-2", service.issueToken(request).token());
        verify(registryClient, times(2)).exchangeEmbedToken(anyMap());
    }

    private EafRegistryProperties properties() {
        EafRegistryProperties properties = new EafRegistryProperties();
        properties.getProject().setCode("bzsdk");
        return properties;
    }

    private EafUser user(String externalUserId) {
        return new EafUser(
                externalUserId,
                externalUserId,
                "系统管理员",
                "D001",
                "研发中心",
                List.of("admin"),
                Map.of());
    }
}
