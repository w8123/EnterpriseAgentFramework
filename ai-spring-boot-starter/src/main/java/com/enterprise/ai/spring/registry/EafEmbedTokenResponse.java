package com.enterprise.ai.spring.registry;

import java.util.Map;

public record EafEmbedTokenResponse(
        String token,
        long expiresIn,
        Map<String, String> sessionHint
) {
}
