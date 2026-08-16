package com.enterprise.ai.runtime.internalauth;

import com.enterprise.ai.common.internalauth.InternalServiceAuthHeaders;
import com.enterprise.ai.common.internalauth.InternalServiceHmac;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InternalAuthNonceStoreTest {

    @Test
    void maxEntriesOneRejectsImmediateReplay() {
        InMemoryInternalAuthNonceStore store = new InMemoryInternalAuthNonceStore();
        long now = 1_700_000_000_000L;
        assertTrue(store.tryConsume("reachai-control-service", "nonce-a", now, 600, 1));
        assertFalse(store.tryConsume("reachai-control-service", "nonce-a", now + 1, 600, 1));
    }

    @Test
    void capacityFullRejectsNewNonceButOldCannotReplay() {
        InMemoryInternalAuthNonceStore store = new InMemoryInternalAuthNonceStore();
        long now = 1_700_000_000_000L;
        assertTrue(store.tryConsume("reachai-control-service", "nonce-a", now, 600, 1));
        assertFalse(store.tryConsume("reachai-control-service", "nonce-b", now + 1, 600, 1));
        assertFalse(store.tryConsume("reachai-control-service", "nonce-a", now + 2, 600, 1));
    }

    @Test
    void expiredNonceCanBeReusedAfterTtl() {
        InMemoryInternalAuthNonceStore store = new InMemoryInternalAuthNonceStore();
        long now = 1_700_000_000_000L;
        assertTrue(store.tryConsume("reachai-control-service", "nonce-a", now, 10, 10));
        long afterTtl = now + 11_000L;
        assertTrue(store.tryConsume("reachai-control-service", "nonce-a", afterTtl, 10, 10));
    }

    @Test
    void cleanupOnlyRemovesExpiredEntries() {
        InMemoryInternalAuthNonceStore store = new InMemoryInternalAuthNonceStore();
        long now = 1_700_000_000_000L;
        assertTrue(store.tryConsume("reachai-control-service", "old", now, 10, 10));
        assertTrue(store.tryConsume("reachai-control-service", "fresh", now + 1, 10, 10));
        long later = now + 11_000L;
        assertTrue(store.tryConsume("reachai-control-service", "next", later, 10, 10));
        // old expired and was cleaned; fresh from previous window also expired (created at now+1).
        assertFalse(store.tryConsume("reachai-control-service", "next", later + 1, 10, 10));
    }

    @Test
    void ttlLessThanTwiceSkewFailsValidation() {
        assertThrows(IllegalStateException.class,
                () -> InternalServiceAuthProperties.validateConfig(300, 599, 1000, 1024));
        InternalServiceAuthProperties.validateConfig(300, 600, 1000, 1024);
    }

    @Test
    void verifierAcceptsPreviousRotationSecretButRejectsUnknownSecret() {
        String active = "new-runtime-internal-service-secret-32bytes";
        String previous = "old-runtime-internal-service-secret-32bytes";
        InternalServiceAuthProperties properties = new InternalServiceAuthProperties(
                active, previous, 300, 600, 1000, 1024);
        properties.validate();
        InternalServiceAuthVerifier verifier = new InternalServiceAuthVerifier(
                properties, new InMemoryInternalAuthNonceStore());
        byte[] body = "{\"message\":\"memory\"}".getBytes(StandardCharsets.UTF_8);
        long now = System.currentTimeMillis();

        assertTrue(verify(verifier, previous, "rotation-old", body, now));
        assertFalse(verify(verifier, "unknown-runtime-secret", "rotation-unknown", body, now));
    }

    @Test
    void productionProfileRejectsShortActiveOrOverlapSecrets() {
        MockEnvironment production = new MockEnvironment();
        production.setActiveProfiles("prod");

        assertThrows(IllegalStateException.class,
                () -> new InternalServiceAuthProperties(
                        "short", "", 300, 600, 1000, 1024, production).validate());
        assertThrows(IllegalStateException.class,
                () -> new InternalServiceAuthProperties(
                        "active-runtime-internal-secret-32bytes", "short",
                        300, 600, 1000, 1024, production).validate());

        new InternalServiceAuthProperties(
                "active-runtime-internal-secret-32bytes",
                "previous-runtime-internal-secret-32bytes",
                300, 600, 1000, 1024, production).validate();
    }

    @Test
    void verifierAcceptsAttestedPlatformSessionOnlyWithAnActorId() {
        String secret = "runtime-internal-service-secret-32bytes";
        InternalServiceAuthProperties properties = new InternalServiceAuthProperties(
                secret, "", 300, 600, 1000, 1024);
        properties.validate();
        InternalServiceAuthVerifier verifier = new InternalServiceAuthVerifier(
                properties, new InMemoryInternalAuthNonceStore());
        byte[] body = new byte[0];
        long now = System.currentTimeMillis();
        String path = "/internal/runtime/session-retention/tenants/tenant-a/policy";

        assertTrue(verifyPlatformSession(verifier, secret, path, "actor-7", "admin-a", body, now));
        assertFalse(verifyPlatformSession(verifier, secret, path, "", "admin-empty", body, now));
    }

    private static boolean verify(InternalServiceAuthVerifier verifier,
                                  String signingSecret,
                                  String nonce,
                                  byte[] body,
                                  long now) {
        String path = "/internal/runtime/agents/execute";
        String timestamp = String.valueOf(now);
        String digest = InternalServiceHmac.bodySha256Hex(body);
        String canonical = InternalServiceHmac.canonical(
                "POST", path, InternalServiceAuthHeaders.CALLER_CONTROL,
                "AGENT", "tenant-a", "user-1", timestamp, nonce, digest);
        return verifier.verify(
                "POST", path, InternalServiceAuthHeaders.CALLER_CONTROL,
                "AGENT", "tenant-a", "user-1", timestamp, nonce, digest,
                InternalServiceHmac.sign(signingSecret, canonical), body, now).isPresent();
    }

    private static boolean verifyPlatformSession(
            InternalServiceAuthVerifier verifier,
            String signingSecret,
            String path,
            String actorId,
            String nonce,
            byte[] body,
            long now) {
        String timestamp = String.valueOf(now);
        String digest = InternalServiceHmac.bodySha256Hex(body);
        String canonical = InternalServiceHmac.canonical(
                "GET", path, InternalServiceAuthHeaders.CALLER_CONTROL,
                InternalServiceAuthHeaders.IDENTITY_SOURCE_PLATFORM_SESSION,
                "tenant-a", actorId, timestamp, nonce, digest);
        return verifier.verify(
                "GET", path, InternalServiceAuthHeaders.CALLER_CONTROL,
                InternalServiceAuthHeaders.IDENTITY_SOURCE_PLATFORM_SESSION,
                "tenant-a", actorId, timestamp, nonce, digest,
                InternalServiceHmac.sign(signingSecret, canonical), body, now).isPresent();
    }
}
