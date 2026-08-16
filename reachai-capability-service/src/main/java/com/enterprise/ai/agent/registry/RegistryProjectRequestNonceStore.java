package com.enterprise.ai.agent.registry;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HexFormat;

/** Database-backed replay gate for public project-credential requests. */
@Component
public class RegistryProjectRequestNonceStore {

    private final JdbcTemplate jdbcTemplate;

    public RegistryProjectRequestNonceStore(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public boolean tryConsume(String projectCode,
                              String appKey,
                              String nonce,
                              long nowMillis,
                              long ttlSeconds,
                              int maxEntries) {
        if (blank(projectCode) || blank(appKey) || blank(nonce)
                || ttlSeconds < 1 || maxEntries < 1) {
            return false;
        }
        Instant now = Instant.ofEpochMilli(nowMillis);
        jdbcTemplate.update("DELETE FROM capability_registry_request_nonce WHERE created_at < ?",
                Timestamp.from(now.minusSeconds(ttlSeconds)));
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM capability_registry_request_nonce", Integer.class);
        if (count != null && count >= maxEntries) {
            return false;
        }
        String nonceDigest = sha256(projectCode.trim() + "\n" + appKey.trim() + "\n" + nonce.trim());
        try {
            return jdbcTemplate.update(
                    "INSERT INTO capability_registry_request_nonce "
                            + "(nonce_digest, project_code, app_key_hash, created_at) VALUES (?, ?, ?, ?)",
                    nonceDigest,
                    projectCode.trim(),
                    sha256(appKey.trim()),
                    Timestamp.from(now)) == 1;
        } catch (DuplicateKeyException duplicate) {
            return false;
        }
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
