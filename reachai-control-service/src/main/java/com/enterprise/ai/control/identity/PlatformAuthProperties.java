package com.enterprise.ai.control.identity;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Explicit platform-console authentication settings. The source distribution
 * defaults to a well-known LOCAL development administrator for an immediately
 * usable open-source quick start. Deployments must explicitly disable LOCAL or
 * replace these bootstrap credentials before exposing the service.
 */
@Data
@Component
@ConfigurationProperties(prefix = "reachai.auth")
public class PlatformAuthProperties {

    public static final String DEVELOPMENT_ADMIN_USERNAME = "admin";
    public static final String DEVELOPMENT_ADMIN_PASSWORD = "admin123";

    private String provider = "LOCAL";

    private Duration sessionTtl = Duration.ofHours(24);

    private Local local = new Local();

    @Data
    public static class Local {
        private boolean enabled = true;
        private BootstrapAdmin bootstrapAdmin = new BootstrapAdmin();
    }

    @Data
    public static class BootstrapAdmin {
        private boolean enabled = true;
        private String username = DEVELOPMENT_ADMIN_USERNAME;
        private String password = DEVELOPMENT_ADMIN_PASSWORD;
    }

    public boolean localPasswordLoginEnabled() {
        return "LOCAL".equalsIgnoreCase(provider) && local != null && local.isEnabled();
    }

    public boolean usesBuiltInDevelopmentAdmin(String username, String password) {
        return DEVELOPMENT_ADMIN_USERNAME.equals(username)
                && DEVELOPMENT_ADMIN_PASSWORD.equals(password);
    }
}
