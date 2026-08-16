package com.enterprise.ai.runtime.internalauth;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RuntimeInternalTransportProductionGuardTest {

    @Test
    void productionDefaultProfileDirectTlsRejectsPlaintextServiceHop() {
        MockEnvironment production = new MockEnvironment();
        production.setDefaultProfiles("prod");

        assertThrows(IllegalStateException.class, () -> new RuntimeInternalTransportProductionGuard(
                "DIRECT_TLS",
                "https://control.internal",
                "https://capability.internal",
                "http://model.internal",
                "https://knowledge.internal/ai",
                production));
    }

    @Test
    void productionAllowsHttpsOrExplicitMtlsMesh() {
        MockEnvironment production = new MockEnvironment();
        production.setActiveProfiles("production");

        assertDoesNotThrow(() -> new RuntimeInternalTransportProductionGuard(
                "DIRECT_TLS",
                "https://control.internal",
                "https://capability.internal",
                "https://model.internal",
                "https://knowledge.internal/ai",
                production));
        assertDoesNotThrow(() -> new RuntimeInternalTransportProductionGuard(
                "MTLS_MESH",
                "http://control:18603",
                "http://capability:18605",
                "http://model:18601",
                "http://knowledge:18602/ai",
                production));
    }
}
