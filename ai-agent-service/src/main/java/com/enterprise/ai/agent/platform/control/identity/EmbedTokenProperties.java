package com.enterprise.ai.agent.platform.control.identity;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

@Data
@Component
@ConfigurationProperties(prefix = "eaf.embed-token")
public class EmbedTokenProperties {

    private String issuer = "reachai";

    private String audience = "reachai-chat-embed";

    private String secret = "dev-only-change-me-reachai-embed-token-secret";

    private String activeKeyId = "default";

    private Map<String, String> secrets = new LinkedHashMap<>();

    private int defaultTokenTtlSeconds = 600;
}
