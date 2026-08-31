package com.enterprise.ai.control.internalauth;

/** Multi-instance anti-replay boundary for Runtime-to-Control HMAC requests. */
public interface ControlInternalAuthNonceStore {

    boolean tryConsume(String caller, String nonce, long nowMillis, long ttlSeconds, int maxEntries);
}
