package com.enterprise.ai.pipeline.document;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DoclingProductionConfigurationGuardTest {

    @Test
    void rejectsMissingCredentialInProduction() {
        DoclingProperties properties = new DoclingProperties();
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("prod");

        assertThrows(IllegalStateException.class, () -> new DoclingProductionConfigurationGuard(
                properties, environment));
    }

    @Test
    void allowsMissingCredentialOutsideProductionAndConfiguredCredentialInProduction() {
        DoclingProperties local = new DoclingProperties();
        assertDoesNotThrow(() -> new DoclingProductionConfigurationGuard(local, new MockEnvironment()));

        DoclingProperties production = new DoclingProperties();
        production.setApiKey("configured-test-value");
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("production");
        assertDoesNotThrow(() -> new DoclingProductionConfigurationGuard(
                production, environment));
    }
}
