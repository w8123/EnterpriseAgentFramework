package com.enterprise.ai.runtime.internalauth;

/**
 * Multi-instance safe anti-replay store for internal auth nonces.
 */
public interface InternalAuthNonceStore {

    /**
     * @return true if nonce was accepted (first use); false if replay / reject
     */
    boolean tryConsume(String caller, String nonce, long nowMillis, long ttlSeconds, int maxEntries);
}
