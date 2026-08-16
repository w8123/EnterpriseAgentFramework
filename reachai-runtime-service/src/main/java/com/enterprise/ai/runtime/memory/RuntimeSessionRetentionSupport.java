package com.enterprise.ai.runtime.memory;

import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;

final class RuntimeSessionRetentionSupport {

    private RuntimeSessionRetentionSupport() {
    }

    static String tenantId(String value) {
        String normalized = required(value, "tenantId", 96);
        if (!normalized.matches("[A-Za-z0-9._:-]+")) {
            throw new IllegalArgumentException("tenantId contains unsupported characters");
        }
        return normalized;
    }

    static String sessionId(String value) {
        String normalized = required(value, "sessionId", 128);
        if (normalized.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("sessionId must not contain control characters");
        }
        return normalized;
    }

    static String userId(String value) {
        String normalized = required(value, "runtimeUserId", 128);
        if (normalized.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("runtimeUserId must not contain control characters");
        }
        return normalized;
    }

    static String reasonCode(String value) {
        String normalized = required(value, "reasonCode", 64).toUpperCase(Locale.ROOT);
        if (!normalized.matches("[A-Z0-9][A-Z0-9_.:-]*")) {
            throw new IllegalArgumentException("reasonCode must be an uppercase machine-readable code");
        }
        return normalized;
    }

    static String referenceId(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        String normalized = value.trim();
        if (normalized.length() > 128 || normalized.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(
                    "referenceId must be at most 128 characters without control characters");
        }
        if (!normalized.matches("[A-Za-z0-9][A-Za-z0-9._:/-]*")) {
            throw new IllegalArgumentException(
                    "referenceId must be a machine-readable external reference");
        }
        return normalized;
    }

    static String actorHash(String actorId) {
        return sha256("actor\n" + required(actorId, "actorId", 128));
    }

    static String sessionHash(String tenantId, String sessionId) {
        return sha256(tenantId(tenantId) + "\n" + sessionId(sessionId));
    }

    static String userHash(String tenantId, String runtimeUserId) {
        return sha256(tenantId(tenantId) + "\n" + userId(runtimeUserId));
    }

    private static String required(String value, String field, int maxLength) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException(field + " is required");
        }
        String normalized = value.trim();
        if (normalized.length() > maxLength) {
            throw new IllegalArgumentException(field + " must be at most " + maxLength + " characters");
        }
        return normalized;
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }
}
