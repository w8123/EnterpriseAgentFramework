package com.enterprise.ai.capability.internal;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CapabilityOutboundTransportPolicyTest {

    @Test
    void productionDirectTlsAlsoProtectsDynamicBusinessEndpoints() {
        MockEnvironment production = new MockEnvironment();
        production.setActiveProfiles("prod");
        CapabilityOutboundTransportPolicy policy = new CapabilityOutboundTransportPolicy(
                "DIRECT_TLS",
                "https://runtime.internal",
                "https://model.internal",
                "https://knowledge.internal/ai",
                production);

        assertDoesNotThrow(() -> policy.requireAllowed(
                "https://orders.example/reachai/capabilities/order.memory.resolve/invoke"));
        assertThrows(IllegalStateException.class, () -> policy.requireAllowed(
                "http://orders.example/reachai/capabilities/order.memory.resolve/invoke"));
    }

    @Test
    void productionDoesNotAllowImplicitPlaintextMode() {
        MockEnvironment production = new MockEnvironment();
        production.setActiveProfiles("production");

        assertThrows(IllegalStateException.class, () -> new CapabilityOutboundTransportPolicy(
                "",
                "http://runtime:18604",
                "http://model:18601",
                "http://knowledge:18602/ai",
                production));
    }
}
