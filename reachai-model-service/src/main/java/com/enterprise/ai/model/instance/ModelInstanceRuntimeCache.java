package com.enterprise.ai.model.instance;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Bounded, short-TTL cache for ACTIVE {@link ModelInstanceRuntime}.
 *
 * <p><b>Consistency model (multi-instance):</b> invalidation on create/update/archive is
 * process-local only. Other model-service replicas may continue serving a previously cached
 * ACTIVE runtime until this TTL elapses. Disabled/archived instances and credential rotations
 * are therefore eventually consistent across instances within {@code ttlMs}.
 *
 * <p>This stage does not use a distributed cache or pub/sub invalidation. Keep {@code ttlMs}
 * short (default 5s, hard cap 30s) so the cross-instance disable/archive/credential window stays
 * bounded. Never log credentials or decrypted config from this cache.
 */
@Component
public class ModelInstanceRuntimeCache {

    private static final Logger log = LoggerFactory.getLogger(ModelInstanceRuntimeCache.class);
    private static final int MAX_ENTRIES = 256;
    /** Default safety window for multi-instance eventual consistency. */
    public static final long DEFAULT_TTL_MS = 5_000L;
    /** Hard upper bound — never allow a longer silent stale window without an explicit redesign. */
    public static final long MAX_TTL_MS = 30_000L;

    private final long ttlMs;
    private final ConcurrentHashMap<String, Entry> entries = new ConcurrentHashMap<>();
    private final AtomicLong hits = new AtomicLong();
    private final AtomicLong misses = new AtomicLong();

    public ModelInstanceRuntimeCache(
            @Value("${model.instance-runtime-cache.ttl-ms:5000}") long ttlMs) {
        this.ttlMs = clampTtl(ttlMs);
    }

    public long ttlMs() {
        return ttlMs;
    }

    public ModelInstanceRuntime getIfPresent(String modelInstanceId) {
        if (modelInstanceId == null || modelInstanceId.isBlank()) {
            return null;
        }
        Entry entry = entries.get(modelInstanceId.trim());
        if (entry == null) {
            misses.incrementAndGet();
            return null;
        }
        if (entry.expiresAtMs < System.currentTimeMillis()) {
            entries.remove(modelInstanceId.trim(), entry);
            misses.incrementAndGet();
            return null;
        }
        hits.incrementAndGet();
        return entry.runtime;
    }

    public void put(String modelInstanceId, ModelInstanceRuntime runtime) {
        if (modelInstanceId == null || modelInstanceId.isBlank() || runtime == null) {
            return;
        }
        // Caller must only put ACTIVE runtimes resolved via getActiveEntity().
        evictIfNeeded();
        entries.put(modelInstanceId.trim(), new Entry(runtime, System.currentTimeMillis() + ttlMs));
    }

    public void invalidate(String modelInstanceId) {
        if (modelInstanceId == null || modelInstanceId.isBlank()) {
            return;
        }
        entries.remove(modelInstanceId.trim());
    }

    public void invalidateAll() {
        entries.clear();
    }

    public long hitCount() {
        return hits.get();
    }

    public long missCount() {
        return misses.get();
    }

    public static long clampTtl(long ttlMs) {
        if (ttlMs <= 0L) {
            return DEFAULT_TTL_MS;
        }
        return Math.min(ttlMs, MAX_TTL_MS);
    }

    private void evictIfNeeded() {
        if (entries.size() < MAX_ENTRIES) {
            return;
        }
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<String, Entry>> iterator = entries.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, Entry> current = iterator.next();
            if (current.getValue().expiresAtMs < now) {
                iterator.remove();
            }
        }
        if (entries.size() < MAX_ENTRIES) {
            return;
        }
        // Drop arbitrary oldest-ish entries when still over capacity.
        int over = entries.size() - MAX_ENTRIES + 1;
        Iterator<String> keys = entries.keySet().iterator();
        while (over > 0 && keys.hasNext()) {
            keys.next();
            keys.remove();
            over--;
        }
        // Never log runtime credentials / decrypted config — size only.
        log.debug("ModelInstanceRuntimeCache capacity eviction applied, size={}", entries.size());
    }

    private record Entry(ModelInstanceRuntime runtime, long expiresAtMs) {
    }
}
