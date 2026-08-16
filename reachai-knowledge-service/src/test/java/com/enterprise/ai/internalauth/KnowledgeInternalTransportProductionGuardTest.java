package com.enterprise.ai.internalauth;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class KnowledgeInternalTransportProductionGuardTest {

    @Test
    void productionDirectTlsRejectsPlaintextModelHop() {
        MockEnvironment production = new MockEnvironment();
        production.setActiveProfiles("prod");

        assertThrows(IllegalStateException.class, () -> new KnowledgeInternalTransportProductionGuard(
                "DIRECT_TLS", "http://model.internal", production));
    }

    @Test
    void productionAllowsHttpsOrExplicitMtlsMesh() {
        MockEnvironment production = new MockEnvironment();
        production.setActiveProfiles("production");

        assertDoesNotThrow(() -> new KnowledgeInternalTransportProductionGuard(
                "DIRECT_TLS", "https://model.internal", production));
        assertDoesNotThrow(() -> new KnowledgeInternalTransportProductionGuard(
                "MTLS_MESH", "http://model:18601", production));
    }
}
