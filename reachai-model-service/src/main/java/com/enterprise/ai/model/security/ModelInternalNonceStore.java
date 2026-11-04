package com.enterprise.ai.model.security;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;

/** Model-owned replay store, shared across replicas and retained across restarts. */
@Component
public class ModelInternalNonceStore {
    private final JdbcTemplate jdbc;
    private final AtomicLong nextCleanupMillis = new AtomicLong(Long.MIN_VALUE);

    public ModelInternalNonceStore(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public boolean tryConsume(String caller, String nonce, Duration ttl) {
        Instant now = Instant.now();
        Timestamp cutoff = Timestamp.from(now.minus(ttl));
        try {
            long due = nextCleanupMillis.get();
            if (now.toEpochMilli() >= due && nextCleanupMillis.compareAndSet(due, now.toEpochMilli() + 60_000L)) {
                jdbc.update("DELETE FROM model_internal_auth_nonce WHERE created_at < ?", cutoff);
            }
            try {
                return insert(caller, nonce, now);
            } catch (DuplicateKeyException replay) {
                if (jdbc.update("DELETE FROM model_internal_auth_nonce WHERE nonce = ? AND created_at < ?",
                        nonce, cutoff) == 0) return false;
                return insert(caller, nonce, now);
            }
        } catch (RuntimeException unavailable) {
            return false;
        }
    }

    private boolean insert(String caller, String nonce, Instant now) {
        return jdbc.update("INSERT INTO model_internal_auth_nonce (nonce, caller, created_at) VALUES (?, ?, ?)",
                nonce, caller, Timestamp.from(now)) == 1;
    }
}
