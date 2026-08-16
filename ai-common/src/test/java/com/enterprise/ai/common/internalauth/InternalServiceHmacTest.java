package com.enterprise.ai.common.internalauth;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InternalServiceHmacTest {

    @Test
    void signAndVerifyIncludeBodyDigest() {
        String bodyDigest = InternalServiceHmac.bodySha256Hex("{\"a\":1}".getBytes(StandardCharsets.UTF_8));
        String canonical = InternalServiceHmac.canonical(
                "POST",
                "/internal/runtime/agents/execute",
                InternalServiceAuthHeaders.CALLER_CONTROL,
                "AGENT",
                "42",
                "1700000000000",
                "nonce-1",
                bodyDigest);
        String signature = InternalServiceHmac.sign("unit-test-secret", canonical);
        assertEquals(64, signature.length());
        assertTrue(InternalServiceHmac.verifyConstantTime("unit-test-secret", canonical, signature));
        assertFalse(InternalServiceHmac.verifyConstantTime("unit-test-secret", canonical, "00" + signature.substring(2)));
    }

    @Test
    void bodyDigestBindsExactBytesIncludingUtf8Chinese() {
        byte[] chinese = "{\"message\":\"查订单\"}".getBytes(StandardCharsets.UTF_8);
        String digest = InternalServiceHmac.bodySha256Hex(chinese);
        assertEquals(64, digest.length());
        assertFalse(InternalServiceHmac.digestEqualsConstantTime(
                digest, InternalServiceHmac.bodySha256Hex("{\"message\":\"other\"}".getBytes(StandardCharsets.UTF_8))));
        assertTrue(InternalServiceHmac.digestEqualsConstantTime(
                digest, InternalServiceHmac.bodySha256Hex(chinese)));
    }

    @Test
    void emptyBodyHasDeterministicDigest() {
        String a = InternalServiceHmac.bodySha256Hex(new byte[0]);
        String b = InternalServiceHmac.bodySha256Hex(null);
        assertEquals(a, b);
        assertEquals(64, a.length());
    }

    @Test
    void identityOrBodyDigestChangeInvalidatesSignature() {
        String digest = InternalServiceHmac.bodySha256Hex("body".getBytes(StandardCharsets.UTF_8));
        String base = InternalServiceHmac.canonical(
                "POST", "/internal/runtime/agents/execute",
                InternalServiceAuthHeaders.CALLER_CONTROL, "AGENT", "user-a",
                "1700000000000", "n1", digest);
        String tamperedUser = InternalServiceHmac.canonical(
                "POST", "/internal/runtime/agents/execute",
                InternalServiceAuthHeaders.CALLER_CONTROL, "AGENT", "user-b",
                "1700000000000", "n1", digest);
        String tamperedBody = InternalServiceHmac.canonical(
                "POST", "/internal/runtime/agents/execute",
                InternalServiceAuthHeaders.CALLER_CONTROL, "AGENT", "user-a",
                "1700000000000", "n1", InternalServiceHmac.bodySha256Hex("x".getBytes(StandardCharsets.UTF_8)));
        String signature = InternalServiceHmac.sign("secret", base);
        assertFalse(InternalServiceHmac.verifyConstantTime("secret", tamperedUser, signature));
        assertFalse(InternalServiceHmac.verifyConstantTime("secret", tamperedBody, signature));
    }

    @Test
    void v2TenantChangeInvalidatesSignature() {
        String digest = InternalServiceHmac.bodySha256Hex("body".getBytes(StandardCharsets.UTF_8));
        String tenantA = InternalServiceHmac.canonical(
                "POST", "/internal/runtime/agents/execute",
                InternalServiceAuthHeaders.CALLER_CONTROL, "AGENT", "tenant-a", "user-a",
                "1700000000000", "n1", digest);
        String tenantB = InternalServiceHmac.canonical(
                "POST", "/internal/runtime/agents/execute",
                InternalServiceAuthHeaders.CALLER_CONTROL, "AGENT", "tenant-b", "user-a",
                "1700000000000", "n1", digest);
        String signature = InternalServiceHmac.sign("secret", tenantA);

        assertTrue(InternalServiceHmac.verifyConstantTime("secret", tenantA, signature));
        assertFalse(InternalServiceHmac.verifyConstantTime("secret", tenantB, signature));
    }

    @Test
    void boundedSecretRingAcceptsActiveOrPreviousButNotUnknownSecret() {
        List<String> accepted = InternalServiceSecretRing.accepted(
                "new-signing-secret", "old-verification-secret,new-signing-secret");
        String canonical = "canonical-message";

        assertEquals(List.of("new-signing-secret", "old-verification-secret"), accepted);
        assertTrue(InternalServiceHmac.verifyAnyConstantTime(
                accepted, canonical, InternalServiceHmac.sign("new-signing-secret", canonical)));
        assertTrue(InternalServiceHmac.verifyAnyConstantTime(
                accepted, canonical, InternalServiceHmac.sign("old-verification-secret", canonical)));
        assertFalse(InternalServiceHmac.verifyAnyConstantTime(
                accepted, canonical, InternalServiceHmac.sign("unknown-secret", canonical)));
    }

    @Test
    void secretRingRejectsUnboundedVerificationSet() {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                () -> InternalServiceSecretRing.accepted("a", "b,c,d,e"));
    }

    @Test
    void productionSecretPolicyRequiresStrongActiveAndOverlapKeys() {
        List<String> strong = InternalServiceSecretRing.accepted(
                "active-internal-service-secret-32bytes",
                "previous-internal-service-secret-32bytes");
        InternalServiceSecretRing.requireStrong(strong.get(0), strong);

        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                () -> InternalServiceSecretRing.requireStrong("short", strong));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                () -> InternalServiceSecretRing.requireStrong(strong.get(0), List.of("short")));
    }
}
