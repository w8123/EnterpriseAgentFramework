package com.enterprise.ai.runtime.internalauth;

import org.junit.jupiter.api.Test;

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
}
