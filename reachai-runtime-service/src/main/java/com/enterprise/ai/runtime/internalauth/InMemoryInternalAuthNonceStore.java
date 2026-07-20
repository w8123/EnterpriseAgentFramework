package com.enterprise.ai.runtime.internalauth;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Single-process nonce store for tests. Never deletes unexpired nonces to free capacity.
 * Production Runtime uses {@link JdbcInternalAuthNonceStore}.
 */
public class InMemoryInternalAuthNonceStore implements InternalAuthNonceStore {

    private final LinkedHashMap<String, Long> entries = new LinkedHashMap<>();

    @Override
    public synchronized boolean tryConsume(String caller, String nonce, long nowMillis, long ttlSeconds, int maxEntries) {
        if (caller == null || nonce == null || caller.isBlank() || nonce.isBlank()) {
            return false;
        }
        if (ttlSeconds < 1 || maxEntries < 1) {
            return false;
        }
        long expireBefore = nowMillis - ttlSeconds * 1000L;
        Iterator<Map.Entry<String, Long>> it = entries.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, Long> entry = it.next();
            if (entry.getValue() < expireBefore) {
                it.remove();
            }
        }
        String key = caller.trim() + "\n" + nonce.trim();
        if (entries.containsKey(key)) {
            return false;
        }
        if (entries.size() >= maxEntries) {
            // Fail-closed: never evict unexpired nonces to accept a new one.
            return false;
        }
        entries.put(key, nowMillis);
        return true;
    }

    /** Test helper: current retained entries after expired cleanup would run. */
    public synchronized int size() {
        return entries.size();
    }
}
