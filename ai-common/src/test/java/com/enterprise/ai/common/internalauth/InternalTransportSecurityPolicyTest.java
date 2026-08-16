package com.enterprise.ai.common.internalauth;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InternalTransportSecurityPolicyTest {

    @Test
    void productionRejectsImplicitOrExplicitPlaintextMode() {
        assertThrows(IllegalStateException.class,
                () -> new InternalTransportSecurityPolicy(true, ""));
        assertThrows(IllegalStateException.class,
                () -> new InternalTransportSecurityPolicy(true, "DEVELOPMENT_PLAINTEXT"));
    }

    @Test
    void directTlsRequiresHttpsWithoutCredentialOrQueryMaterial() {
        InternalTransportSecurityPolicy policy = new InternalTransportSecurityPolicy(true, "direct_tls");

        assertDoesNotThrow(() -> policy.requireBaseUrl(
                "https://runtime.internal.example/ai", "runtime url"));
        assertThrows(IllegalStateException.class,
                () -> policy.requireBaseUrl("http://runtime.internal.example", "runtime url"));
        assertThrows(IllegalStateException.class,
                () -> policy.requireBaseUrl("https://user:secret@runtime.internal.example", "runtime url"));
        assertThrows(IllegalStateException.class,
                () -> policy.requireBaseUrl("https://runtime.internal.example?token=secret", "runtime url"));
        assertDoesNotThrow(() -> policy.requireRequestUrl(
                "https://runtime.internal.example/orders?id=O-1", "request url"));
    }

    @Test
    void explicitMtlsMeshAllowsSidecarPlaintextHop() {
        InternalTransportSecurityPolicy policy = new InternalTransportSecurityPolicy(true, "MTLS_MESH");

        assertDoesNotThrow(() -> policy.requireBaseUrl(
                "http://reachai-runtime:18604", "runtime url"));
        assertEquals(InternalTransportSecurityPolicy.MTLS_MESH, policy.mode());
    }

    @Test
    void developmentStillRejectsMalformedOrNonHttpEndpoints() {
        InternalTransportSecurityPolicy policy = new InternalTransportSecurityPolicy(false, null);

        assertDoesNotThrow(() -> policy.requireBaseUrl("http://localhost:18604", "runtime url"));
        assertThrows(IllegalStateException.class,
                () -> policy.requireBaseUrl("file:///tmp/runtime", "runtime url"));
        assertTrue(InternalTransportSecurityPolicy.isProduction("test", "PRODUCTION"));
    }
}
