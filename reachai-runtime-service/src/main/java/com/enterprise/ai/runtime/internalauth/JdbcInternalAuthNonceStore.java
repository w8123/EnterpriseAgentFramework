package com.enterprise.ai.runtime.internalauth;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Instant;

/**
 * Shared MySQL-backed nonce store. Unique primary key on {@code nonce} makes consume atomic.
 * Never deletes unexpired nonces to free capacity — at capacity, new nonces are rejected.
 */
@Component
public class JdbcInternalAuthNonceStore implements InternalAuthNonceStore {

    private final JdbcTemplate jdbcTemplate;

    public JdbcInternalAuthNonceStore(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public boolean tryConsume(String caller, String nonce, long nowMillis, long ttlSeconds, int maxEntries) {
        if (caller == null || nonce == null || caller.isBlank() || nonce.isBlank()) {
            return false;
        }
        if (ttlSeconds < 1 || maxEntries < 1) {
            return false;
        }
        Instant now = Instant.ofEpochMilli(nowMillis);
        Instant expireBefore = now.minusSeconds(ttlSeconds);
        // Only expired rows may be removed.
        jdbcTemplate.update(
                "DELETE FROM runtime_internal_auth_nonce WHERE created_at < ?",
                Timestamp.from(expireBefore));
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM runtime_internal_auth_nonce", Integer.class);
        if (count != null && count >= maxEntries) {
            return false;
        }
        try {
            int inserted = jdbcTemplate.update(
                    "INSERT INTO runtime_internal_auth_nonce (nonce, caller, created_at) VALUES (?, ?, ?)",
                    nonce.trim(), caller.trim(), Timestamp.from(now));
            return inserted == 1;
        } catch (DuplicateKeyException ex) {
            return false;
        }
    }
}
