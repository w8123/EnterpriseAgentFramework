package com.enterprise.ai.control.internalauth;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Locale;

/** Fails Control startup before it can sign trusted calls with a weak production key. */
@Component
public class ControlInternalServiceSecretProductionGuard {

    private final String serviceSecret;
    private final Environment environment;

    public ControlInternalServiceSecretProductionGuard(
            @Value("${reachai.internal.service-secret:${REACHAI_INTERNAL_SERVICE_SECRET:}}")
            String serviceSecret,
            Environment environment) {
        this.serviceSecret = serviceSecret == null ? "" : serviceSecret.trim();
        this.environment = environment;
    }

    @PostConstruct
    void validate() {
        if (isProduction(environment) && serviceSecret.length() < 32) {
            throw new IllegalStateException(
                    "reachai.internal.service-secret must contain at least 32 characters in production");
        }
    }

    private static boolean isProduction(Environment environment) {
        return environment != null && Arrays.stream(environment.getActiveProfiles())
                .map(profile -> profile.trim().toLowerCase(Locale.ROOT))
                .anyMatch(profile -> "prod".equals(profile) || "production".equals(profile));
    }
}
