package com.enterprise.ai.control.internalauth;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Instant;

/**
 * Shared Control-owned nonce store. The primary key makes first consumption
 * atomic across Control replicas; only expired rows are removed.
 */
@Component
public class JdbcControlInternalAuthNonceStore implements ControlInternalAuthNonceStore {

    private final JdbcTemplate jdbcTemplate;

    public JdbcControlInternalAuthNonceStore(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public boolean tryConsume(String caller,
                              String nonce,
                              long nowMillis,
                              long ttlSeconds,
                              int maxEntries) {
        if (caller == null || nonce == null || caller.isBlank() || nonce.isBlank()
                || ttlSeconds < 1L || maxEntries < 1) {
            return false;
        }
        Instant now = Instant.ofEpochMilli(nowMillis);
        jdbcTemplate.update(
                "DELETE FROM control_internal_auth_nonce WHERE created_at < ?",
                Timestamp.from(now.minusSeconds(ttlSeconds)));
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM control_internal_auth_nonce", Integer.class);
        if (count != null && count >= maxEntries) {
            return false;
        }
        try {
            return jdbcTemplate.update(
                    "INSERT INTO control_internal_auth_nonce (nonce, caller, created_at) VALUES (?, ?, ?)",
                    nonce.trim(), caller.trim(), Timestamp.from(now)) == 1;
        } catch (DuplicateKeyException replay) {
            return false;
        }
    }
}
