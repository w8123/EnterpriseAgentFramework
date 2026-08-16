package com.enterprise.ai.capability.internalauth;

/** Database-backed, multi-instance-safe replay shield for internal requests. */
public interface CapabilityInternalAuthNonceStore {
    boolean tryConsume(String caller, String nonce, long nowMillis, long ttlSeconds, int maxEntries);
}
