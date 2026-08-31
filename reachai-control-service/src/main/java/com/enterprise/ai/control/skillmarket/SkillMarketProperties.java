package com.enterprise.ai.control.skillmarket;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Data
@Component
@ConfigurationProperties(prefix = "reachai.skill-market")
public class SkillMarketProperties {

    private boolean enabled = true;
    private Duration connectTimeout = Duration.ofSeconds(5);
    private Duration readTimeout = Duration.ofSeconds(30);
    private long maxJsonBytes = 2L * 1024L * 1024L;
    private boolean allowSyntheticProxyDns;
    private SkillsSh skillsSh = new SkillsSh();
    private Github github = new Github();

    @Data
    public static class SkillsSh {
        private String baseUrl = "https://skills.sh";
        private String oidcToken;
        private boolean legacySearchEnabled = true;
    }

    @Data
    public static class Github {
        private String apiBaseUrl = "https://api.github.com";
        private String token;
    }
}
