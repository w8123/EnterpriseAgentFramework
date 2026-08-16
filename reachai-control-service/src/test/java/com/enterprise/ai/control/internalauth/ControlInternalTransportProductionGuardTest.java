package com.enterprise.ai.control.internalauth;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ControlInternalTransportProductionGuardTest {

    @Test
    void productionDirectTlsRejectsAnyPlaintextServiceHop() {
        MockEnvironment production = new MockEnvironment();
        production.setActiveProfiles("prod");

        assertThrows(IllegalStateException.class, () -> new ControlInternalTransportProductionGuard(
                "DIRECT_TLS",
                "https://runtime.internal",
                "http://capability.internal",
                "https://model.internal",
                "https://knowledge.internal/ai",
                production));
    }

    @Test
    void productionAllowsHttpsOrExplicitMtlsMesh() {
        MockEnvironment production = new MockEnvironment();
        production.setActiveProfiles("production");

        assertDoesNotThrow(() -> new ControlInternalTransportProductionGuard(
                "DIRECT_TLS",
                "https://runtime.internal",
                "https://capability.internal",
                "https://model.internal",
                "https://knowledge.internal/ai",
                production));
        assertDoesNotThrow(() -> new ControlInternalTransportProductionGuard(
                "MTLS_MESH",
                "http://runtime:18604",
                "http://capability:18605",
                "http://model:18601",
                "http://knowledge:18602/ai",
                production));
    }
}
