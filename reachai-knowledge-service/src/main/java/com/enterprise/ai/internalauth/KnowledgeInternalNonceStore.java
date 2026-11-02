package com.enterprise.ai.internalauth;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Shared replay store for signed Knowledge ingress across all replicas. The database primary
 * key keeps replay protection without Redis, so Knowledge needs no extra infrastructure.
 */
@Component
public class KnowledgeInternalNonceStore {

    private final JdbcTemplate jdbcTemplate;
    private final AtomicLong nextCleanupMillis = new AtomicLong(Long.MIN_VALUE);

    public KnowledgeInternalNonceStore(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** Database unavailability fails closed; accepting a replay is never a fallback. */
    public boolean tryConsume(String caller, String nonce, Duration ttl) {
        if (caller == null || nonce == null || caller.isBlank() || nonce.isBlank()
                || ttl == null || ttl.isNegative() || ttl.isZero()) {
            return false;
        }
        Instant now = Instant.now();
        Timestamp expireBefore = Timestamp.from(now.minus(ttl));
        try {
            cleanup(now.toEpochMilli(), ttl, expireBefore);
            try {
                return insert(caller.trim(), nonce.trim(), Timestamp.from(now));
            } catch (DuplicateKeyException replay) {
                // An expired nonce may be reused before the periodic sweep, never a live one.
                if (jdbcTemplate.update("DELETE FROM knowledge_internal_auth_nonce WHERE nonce = ? AND created_at < ?",
                        nonce.trim(), expireBefore) == 0) {
                    return false;
                }
                return insert(caller.trim(), nonce.trim(), Timestamp.from(now));
            }
        } catch (RuntimeException unavailable) {
            return false;
        }
    }

    private boolean insert(String caller, String nonce, Timestamp now) {
        return jdbcTemplate.update(
                "INSERT INTO knowledge_internal_auth_nonce (nonce, caller, created_at) VALUES (?, ?, ?)",
                nonce, caller, now) == 1;
    }

    private void cleanup(long nowMillis, Duration ttl, Timestamp expireBefore) {
        long due = nextCleanupMillis.get();
        if (nowMillis < due || !nextCleanupMillis.compareAndSet(due,
                nowMillis + Math.min(ttl.toMillis(), 60_000L))) {
            return;
        }
        jdbcTemplate.update("DELETE FROM knowledge_internal_auth_nonce WHERE created_at < ?", expireBefore);
    }
}
