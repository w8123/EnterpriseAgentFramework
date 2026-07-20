package com.enterprise.ai.runtime.internalauth;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Readiness signal for HMAC internal auth. Never exposes secret/signature/digest values.
 */
@Component("internalServiceAuth")
public class InternalServiceAuthHealthIndicator implements HealthIndicator {

    private final InternalServiceAuthProperties properties;

    public InternalServiceAuthHealthIndicator(InternalServiceAuthProperties properties) {
        this.properties = properties;
    }

    @Override
    public Health health() {
        if (!properties.secretConfigured()) {
            return Health.down()
                    .withDetail("code", "INTERNAL_AUTH_NOT_CONFIGURED")
                    .withDetail("secretConfigured", false)
                    .build();
        }
        return Health.up()
                .withDetail("code", "INTERNAL_AUTH_CONFIGURED")
                .withDetail("secretConfigured", true)
                .withDetail("skewSeconds", properties.skewSeconds())
                .withDetail("nonceTtlSeconds", properties.nonceTtlSeconds())
                .build();
    }
}
