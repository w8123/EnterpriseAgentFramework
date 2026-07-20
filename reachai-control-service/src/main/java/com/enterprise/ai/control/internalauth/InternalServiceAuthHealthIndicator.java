package com.enterprise.ai.control.internalauth;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Readiness signal for Control→Runtime HMAC signing. Never exposes secret values.
 */
@Component("internalServiceAuth")
public class InternalServiceAuthHealthIndicator implements HealthIndicator {

    private final InternalServiceAuthSigner signer;

    public InternalServiceAuthHealthIndicator(InternalServiceAuthSigner signer) {
        this.signer = signer;
    }

    @Override
    public Health health() {
        if (!signer.secretConfigured()) {
            return Health.down()
                    .withDetail("code", "INTERNAL_AUTH_NOT_CONFIGURED")
                    .withDetail("secretConfigured", false)
                    .build();
        }
        return Health.up()
                .withDetail("code", "INTERNAL_AUTH_CONFIGURED")
                .withDetail("secretConfigured", true)
                .build();
    }
}
