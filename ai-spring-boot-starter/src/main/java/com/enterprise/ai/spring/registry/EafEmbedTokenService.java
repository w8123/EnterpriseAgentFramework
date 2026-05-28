package com.enterprise.ai.spring.registry;

import org.springframework.util.StringUtils;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class EafEmbedTokenService {

    private static final long REFRESH_SKEW_SECONDS = 30;

    private final EafRegistryProperties properties;
    private final EafCurrentUserProvider currentUserProvider;
    private final EafRegistryClient registryClient;
    private final Clock clock;
    private final Map<CacheKey, CachedToken> tokenCache = new ConcurrentHashMap<>();

    public EafEmbedTokenService(EafRegistryProperties properties,
                                EafCurrentUserProvider currentUserProvider,
                                EafRegistryClient registryClient) {
        this(properties, currentUserProvider, registryClient, Clock.systemUTC());
    }

    EafEmbedTokenService(EafRegistryProperties properties,
                         EafCurrentUserProvider currentUserProvider,
                         EafRegistryClient registryClient,
                         Clock clock) {
        this.properties = properties;
        this.currentUserProvider = currentUserProvider;
        this.registryClient = registryClient;
        this.clock = clock;
    }

    public EafEmbedTokenResponse issueToken(EafEmbedTokenRequest request) {
        if (request == null || !StringUtils.hasText(request.agentId())) {
            throw new IllegalArgumentException("agentId is required");
        }
        if (!StringUtils.hasText(request.pageInstanceId())) {
            throw new IllegalArgumentException("pageInstanceId is required");
        }
        if (!StringUtils.hasText(request.origin())) {
            throw new IllegalArgumentException("origin is required");
        }
        EafUser user = currentUserProvider.currentUser();
        if (user == null || !StringUtils.hasText(user.externalUserId())) {
            throw new IllegalArgumentException("EafCurrentUserProvider must return externalUserId");
        }
        CacheKey cacheKey = new CacheKey(
                properties.getProject().getCode(),
                user.externalUserId(),
                firstNonBlank(user.globalUserId(), user.externalUserId()),
                request.agentId(),
                request.pageInstanceId(),
                request.origin(),
                request.route());
        CachedToken cached = tokenCache.get(cacheKey);
        if (cached != null && cached.isUsable(clock.instant())) {
            return cached.response();
        }

        Map<String, Object> principal = new LinkedHashMap<>();
        principal.put("externalUserId", user.externalUserId());
        principal.put("globalUserId", firstNonBlank(user.globalUserId(), user.externalUserId()));
        principal.put("userName", user.userName());
        principal.put("deptId", user.deptId());
        principal.put("deptName", user.deptName());
        principal.put("roles", user.roles());
        principal.put("attributes", user.attributes());

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("projectCode", properties.getProject().getCode());
        body.put("agentId", request.agentId());
        body.put("pageInstanceId", request.pageInstanceId());
        body.put("route", request.route());
        body.put("origin", request.origin());
        body.put("principal", principal);
        EafEmbedTokenResponse response = registryClient.exchangeEmbedToken(body);
        if (response != null && StringUtils.hasText(response.token()) && response.expiresIn() > 0) {
            tokenCache.put(cacheKey, new CachedToken(response, clock.instant().plusSeconds(response.expiresIn())));
        }
        return response;
    }

    private static String firstNonBlank(String value, String fallback) {
        return StringUtils.hasText(value) ? value : fallback;
    }

    private record CacheKey(String projectCode,
                            String externalUserId,
                            String globalUserId,
                            String agentId,
                            String pageInstanceId,
                            String origin,
                            String route) {
    }

    private record CachedToken(EafEmbedTokenResponse response, Instant expiresAt) {
        boolean isUsable(Instant now) {
            return expiresAt != null && expiresAt.minusSeconds(REFRESH_SKEW_SECONDS).isAfter(now);
        }
    }
}
