package com.enterprise.ai.control.platform;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Durable, project-scoped tombstones for host login sessions. */
@Service
public class PlatformEmbedBusinessSessionRevocation {
    private final JdbcTemplate jdbc;

    public PlatformEmbedBusinessSessionRevocation(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void requireActive(String projectCode, String businessSessionId) {
        String key = key(projectCode, businessSessionId);
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM control_embed_token_revocation WHERE jti = ?", Integer.class, key);
        if (count == null || count != 0) {
            throw new PlatformEmbedTokenException("embed business session is revoked");
        }
    }

    public void revoke(String projectCode, String businessSessionId) {
        jdbc.update("INSERT INTO control_embed_token_revocation (jti, reason) VALUES (?, ?) "
                        + "ON DUPLICATE KEY UPDATE jti = VALUES(jti)",
                key(projectCode, businessSessionId), "BUSINESS_SESSION_LOGOUT");
    }

    static String key(String projectCode, String businessSessionId) {
        if (!StringUtils.hasText(projectCode) || !StringUtils.hasText(businessSessionId)
                || businessSessionId.length() < 32 || businessSessionId.length() > 128
                || !businessSessionId.matches("[A-Za-z0-9_-]+")) {
            throw new PlatformEmbedTokenException("embed business session id is invalid");
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(projectCode.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update(businessSessionId.getBytes(StandardCharsets.UTF_8));
            return "business-session-" + HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }
}
