package com.enterprise.ai.control.internalauth;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ControlInternalServiceSecretProductionGuardTest {

    @Test
    void productionRejectsWeakSigningSecretWhileDevelopmentRemainsCompatible() {
        MockEnvironment production = new MockEnvironment();
        production.setActiveProfiles("prod");
        assertThrows(IllegalStateException.class,
                () -> new ControlInternalServiceSecretProductionGuard("short", production).validate());

        assertDoesNotThrow(() -> new ControlInternalServiceSecretProductionGuard(
                "control-internal-service-secret-32bytes", production).validate());
        assertDoesNotThrow(() -> new ControlInternalServiceSecretProductionGuard(
                "short", new MockEnvironment()).validate());
    }
}
