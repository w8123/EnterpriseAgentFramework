package com.enterprise.ai.pipeline.document;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DoclingProductionConfigurationGuardTest {

    @Test
    void rejectsLocalDevelopmentCredentialInProduction() {
        DoclingProperties properties = new DoclingProperties();
        properties.setApiKey(DoclingProductionConfigurationGuard.LOCAL_DEVELOPMENT_API_KEY);
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("prod");

        assertThrows(IllegalStateException.class, () -> new DoclingProductionConfigurationGuard(
                properties, environment));
    }

    @Test
    void allowsLocalDefaultOutsideProductionAndStrongSecretInProduction() {
        DoclingProperties local = new DoclingProperties();
        local.setApiKey(DoclingProductionConfigurationGuard.LOCAL_DEVELOPMENT_API_KEY);
        assertDoesNotThrow(() -> new DoclingProductionConfigurationGuard(local, new MockEnvironment()));

        DoclingProperties production = new DoclingProperties();
        production.setApiKey("docling-production-secret-value");
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("production");
        assertDoesNotThrow(() -> new DoclingProductionConfigurationGuard(
                production, environment));
    }
}
