package com.enterprise.ai.spring.registry;

import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

public class EafAgentClient {

    private final EafRegistryProperties properties;

    private final RestClient restClient;

    public EafAgentClient(EafRegistryProperties properties) {
        this.properties = properties;
        this.restClient = RestClient.builder().baseUrl(trimTrailingSlash(properties.getRegistry().getUrl())).build();
    }

    public Map<?, ?> chat(String agentKey, String message) {
        return chat(agentKey, new AgentChatRequest(message, null, null, null, null));
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
