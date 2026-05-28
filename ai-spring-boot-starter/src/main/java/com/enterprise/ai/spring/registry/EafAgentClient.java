package com.enterprise.ai.spring.registry;

import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class EafAgentClient {

    private final EafRegistryProperties properties;

    private final RestClient restClient;

    private final EafCurrentUserProvider currentUserProvider;

    public EafAgentClient(EafRegistryProperties properties) {
        this(properties, null);
    }

    public EafAgentClient(EafRegistryProperties properties, EafCurrentUserProvider currentUserProvider) {
        this.properties = properties;
        this.restClient = RestClient.builder().baseUrl(trimTrailingSlash(properties.getRegistry().getUrl())).build();
        this.currentUserProvider = currentUserProvider;
    }

    public Map<?, ?> chat(String agentKey, String message) {
        return chat(agentKey, requestForCurrentUser(message, null, null));
    }

    public Map<?, ?> chat(String agentKey, AgentChatRequest request) {
        if (!StringUtils.hasText(agentKey)) {
            throw new IllegalArgumentException("agentKey 不能为空");
        }
        return restClient.post()
                .uri("/api/v1/agents/{key}/chat", agentKey)
                .body(request)
                .retrieve()
                .body(Map.class);
    }

    public AgentChatRequest requestForCurrentUser(String message,
                                                  String sessionId,
                                                  Map<String, Object> context) {
        if (currentUserProvider == null) {
            return new AgentChatRequest(message, sessionId, null, null, context);
        }
        EafUser user = currentUserProvider.currentUser();
        if (user == null || !StringUtils.hasText(user.externalUserId())) {
            throw new IllegalArgumentException("EafCurrentUserProvider must return externalUserId");
        }
        Map<String, Object> merged = new LinkedHashMap<>();
        if (context != null) {
            merged.putAll(context);
        }
        String appId = properties.getProject().getCode();
        String globalUserId = StringUtils.hasText(user.globalUserId()) ? user.globalUserId() : user.externalUserId();
        Map<String, Object> principal = new LinkedHashMap<>();
        principal.put("appId", appId);
        principal.put("projectCode", appId);
        principal.put("externalUserId", user.externalUserId());
        principal.put("globalUserId", globalUserId);
        principal.put("userName", user.userName());
        principal.put("deptId", user.deptId());
        principal.put("deptName", user.deptName());
        principal.put("roles", user.roles());
        principal.put("attributes", user.attributes());
        merged.putAll(principal);
        merged.put("principal", principal);
        return new AgentChatRequest(
                message,
                sessionId,
                user.externalUserId(),
                user.roles(),
                merged);
    }

    public record AgentChatRequest(
            String message,
            String sessionId,
            String userId,
            List<String> roles,
            Map<String, Object> context
    ) {
    }

    private String trimTrailingSlash(String url) {
        if (url == null) {
            return "";
        }
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
