package com.enterprise.ai.reach.sdk.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.UUID;

/**
 * Signs the exact method, path and body bytes of a project-scoped ReachAI
 * request. Unlike the legacy registry heartbeat signature, this protocol is
 * safe for business-data mutation requests.
 */
public final class ReachAiProjectRequestSigner {

    public static final String PROTOCOL = "REACHAI_PROJECT_REQUEST_V1";

    private ReachAiProjectRequestSigner() {
    }

    public static ReachAiProjectRequestHeaders sign(String appKey,
                                                    String appSecret,
                                                    String projectCode,
                                                    String method,
                                                    String path,
                                                    byte[] exactBodyBytes) {
        return sign(appKey, appSecret, projectCode, method, path, exactBodyBytes,
                String.valueOf(System.currentTimeMillis()), UUID.randomUUID().toString());
    }

    public static ReachAiProjectRequestHeaders sign(String appKey,
                                                    String appSecret,
                                                    String projectCode,
                                                    String method,
                                                    String path,
                                                    byte[] exactBodyBytes,
                                                    String timestamp,
                                                    String nonce) {
        String digest = bodySha256Hex(exactBodyBytes);
        String message = canonical(method, path, projectCode, appKey, timestamp, nonce, digest);
        return new ReachAiProjectRequestHeaders(
                required(appKey, "appKey"),
                required(timestamp, "timestamp"),
                required(nonce, "nonce"),
                digest,
                ReachAiSigner.sign(appSecret, message));
    }

    public static String canonical(String method,
                                   String path,
                                   String projectCode,
                                   String appKey,
                                   String timestamp,
                                   String nonce,
                                   String bodySha256) {
        String normalizedDigest = required(bodySha256, "bodySha256").toLowerCase(Locale.ENGLISH);
        if (!normalizedDigest.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("bodySha256 must be a SHA-256 hex digest");
        }
        return PROTOCOL
                + "\n" + required(method, "method").toUpperCase(Locale.ENGLISH)
                + "\n" + requiredPath(path)
                + "\n" + required(projectCode, "projectCode")
                + "\n" + required(appKey, "appKey")
                + "\n" + required(timestamp, "timestamp")
                + "\n" + required(nonce, "nonce")
                + "\n" + normalizedDigest;
    }

    public static String bodySha256Hex(byte[] body) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(body == null ? new byte[0] : body);
            StringBuilder out = new StringBuilder(digest.length * 2);
            for (byte value : digest) {
                out.append(String.format("%02x", value & 0xff));
            }
            return out.toString();
        } catch (Exception impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    private static String requiredPath(String value) {
        String path = required(value, "path");
        if (!path.startsWith("/") || path.indexOf('?') >= 0 || path.indexOf('#') >= 0
                || path.contains("..") || path.indexOf('\r') >= 0 || path.indexOf('\n') >= 0) {
            throw new IllegalArgumentException("path must be an absolute canonical path without query or fragment");
        }
        return path;
    }

    private static String required(String value, String label) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(label + " is required");
        }
        String normalized = value.trim();
        if (normalized.indexOf('\r') >= 0 || normalized.indexOf('\n') >= 0) {
            throw new IllegalArgumentException(label + " contains unsupported characters");
        }
        return normalized;
    }
}
