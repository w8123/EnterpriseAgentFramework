package com.enterprise.ai.capability.internalauth;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Instant;

@Component
public class JdbcCapabilityInternalAuthNonceStore implements CapabilityInternalAuthNonceStore {

    private final JdbcTemplate jdbcTemplate;

    public JdbcCapabilityInternalAuthNonceStore(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public boolean tryConsume(String caller, String nonce, long nowMillis, long ttlSeconds, int maxEntries) {
        if (caller == null || caller.isBlank() || nonce == null || nonce.isBlank()
                || ttlSeconds < 1 || maxEntries < 1) {
            return false;
        }
        Instant now = Instant.ofEpochMilli(nowMillis);
        jdbcTemplate.update("DELETE FROM capability_internal_auth_nonce WHERE created_at < ?",
                Timestamp.from(now.minusSeconds(ttlSeconds)));
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM capability_internal_auth_nonce", Integer.class);
        if (count != null && count >= maxEntries) {
            return false;
        }
        try {
            return jdbcTemplate.update(
                    "INSERT INTO capability_internal_auth_nonce (nonce, caller, created_at) VALUES (?, ?, ?)",
                    nonce.trim(), caller.trim(), Timestamp.from(now)) == 1;
        } catch (DuplicateKeyException duplicate) {
            return false;
        }
    }
}
