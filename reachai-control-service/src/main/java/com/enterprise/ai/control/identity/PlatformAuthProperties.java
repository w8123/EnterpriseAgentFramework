package com.enterprise.ai.control.identity;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Explicit platform-console authentication settings. LOCAL remains useful for
 * isolated development, but is fail-closed unless deliberately enabled.
 */
@Data
@Component
@ConfigurationProperties(prefix = "reachai.auth")
public class PlatformAuthProperties {

    private String provider = "LOCAL";

    private Duration sessionTtl = Duration.ofHours(24);

    private Local local = new Local();

    @Data
    public static class Local {
        private boolean enabled;
        private BootstrapAdmin bootstrapAdmin = new BootstrapAdmin();
    }

    @Data
    public static class BootstrapAdmin {
        private boolean enabled;
        private String username;
        private String password;
    }

    public boolean localPasswordLoginEnabled() {
        return "LOCAL".equalsIgnoreCase(provider) && local != null && local.isEnabled();
    }
}
