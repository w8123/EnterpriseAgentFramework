package com.enterprise.ai.common.internalauth;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.Objects;

/**
 * HMAC-SHA256 helpers for Control → Runtime internal calls.
 * Canonical string:
 * V1: {@code METHOD\nPATH\nCALLER\nSOURCE\nUSER_ID\nTIMESTAMP\nNONCE\nBODY_SHA256}
 * V2: {@code METHOD\nPATH\nCALLER\nSOURCE\nTENANT_ID\nUSER_ID\nTIMESTAMP\nNONCE\nBODY_SHA256}
 */
public final class InternalServiceHmac {

    private static final String HMAC_SHA256 = "HmacSHA256";
    private static final String SHA_256 = "SHA-256";

    private InternalServiceHmac() {
    }

    public static String canonical(String method,
                                   String path,
                                   String caller,
                                   String identitySource,
                                   String identityUserId,
                                   String timestamp,
                                   String nonce,
                                   String bodySha256) {
        return normalize(method).toUpperCase(Locale.ROOT)
                + "\n" + normalize(path)
                + "\n" + normalize(caller)
                + "\n" + normalize(identitySource).toUpperCase(Locale.ROOT)
                + "\n" + normalize(identityUserId)
                + "\n" + normalize(timestamp)
                + "\n" + normalize(nonce)
                + "\n" + normalize(bodySha256).toLowerCase(Locale.ROOT);
    }

    public static String canonical(String method,
                                   String path,
                                   String caller,
                                   String identitySource,
                                   String identityTenantId,
                                   String identityUserId,
                                   String timestamp,
                                   String nonce,
                                   String bodySha256) {
        return normalize(method).toUpperCase(Locale.ROOT)
                + "\n" + normalize(path)
                + "\n" + normalize(caller)
                + "\n" + normalize(identitySource).toUpperCase(Locale.ROOT)
                + "\n" + normalize(identityTenantId)
                + "\n" + normalize(identityUserId)
                + "\n" + normalize(timestamp)
                + "\n" + normalize(nonce)
                + "\n" + normalize(bodySha256).toLowerCase(Locale.ROOT);
    }

    /** SHA-256 hex of the exact HTTP body bytes that will be / were sent. */
    public static String bodySha256Hex(byte[] bodyBytes) {
        byte[] bytes = bodyBytes == null ? new byte[0] : bodyBytes;
        try {
            MessageDigest digest = MessageDigest.getInstance(SHA_256);
            return toHex(digest.digest(bytes));
        } catch (Exception ex) {
            throw new IllegalStateException("body digest failed", ex);
        }
    }

    public static String sign(String secret, String canonicalMessage) {
        Objects.requireNonNull(secret, "secret");
        Objects.requireNonNull(canonicalMessage, "canonicalMessage");
        if (secret.isBlank()) {
            throw new IllegalStateException("internal service secret is required");
        }
        try {
            Mac mac = Mac.getInstance(HMAC_SHA256);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC_SHA256));
            byte[] digest = mac.doFinal(canonicalMessage.getBytes(StandardCharsets.UTF_8));
            return toHex(digest);
        } catch (Exception ex) {
            throw new IllegalStateException("internal service signing failed", ex);
        }
    }

    public static boolean verifyConstantTime(String secret, String canonicalMessage, String providedSignature) {
        if (secret == null || secret.isBlank()
                || canonicalMessage == null
                || providedSignature == null
                || providedSignature.isBlank()) {
            return false;
        }
        try {
            String expected = sign(secret, canonicalMessage);
            byte[] a = expected.getBytes(StandardCharsets.UTF_8);
            byte[] b = providedSignature.trim().toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8);
            return MessageDigest.isEqual(a, b);
        } catch (RuntimeException ex) {
            return false;
        }
    }

    /**
     * Verifies against every configured key without returning early on a match.
     * Signing always uses the active key; this method is only for a bounded
     * verification overlap during rolling rotation.
     */
    public static boolean verifyAnyConstantTime(Iterable<String> secrets,
                                                String canonicalMessage,
                                                String providedSignature) {
        if (secrets == null) {
            return false;
        }
        boolean evaluated = false;
        boolean matched = false;
        for (String secret : secrets) {
            if (secret == null || secret.isBlank()) {
                continue;
            }
            evaluated = true;
            matched |= verifyConstantTime(secret, canonicalMessage, providedSignature);
        }
        return evaluated && matched;
    }

    public static boolean digestEqualsConstantTime(String expectedHex, String providedHex) {
        if (expectedHex == null || providedHex == null) {
            return false;
        }
        byte[] a = expectedHex.trim().toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8);
        byte[] b = providedHex.trim().toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(a, b);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }

    private static String toHex(byte[] bytes) {
        StringBuilder out = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            out.append(String.format("%02x", b & 0xff));
        }
        return out.toString();
    }
}
