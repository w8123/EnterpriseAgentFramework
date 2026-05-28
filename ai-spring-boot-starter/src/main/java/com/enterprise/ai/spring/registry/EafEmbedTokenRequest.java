package com.enterprise.ai.spring.registry;

public record EafEmbedTokenRequest(
        String agentId,
        String pageInstanceId,
        String route,
        String origin
) {
}
