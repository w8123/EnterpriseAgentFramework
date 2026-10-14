package com.enterprise.ai.control.a2a.application.identity;

import org.springframework.security.crypto.password.PasswordEncoder;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;

/** Versioned, domain-separated prehash keeps complete API keys within BCrypt's byte limit. */
final class A2aApiKeyHash {

    private static final String PREFIX = "{a2a-sha256-v1}";
    private static final byte[] DOMAIN = "reachai:a2a:api-key:hash:v1\0".getBytes(StandardCharsets.UTF_8);

    private A2aApiKeyHash() {
    }

    static String encode(PasswordEncoder encoder, String raw) {
        return PREFIX + encoder.encode(prehash(raw));
    }

    static boolean matches(PasswordEncoder encoder, String raw, String stored) {
        String candidate = raw == null ? "" : raw;
        if (stored.startsWith(PREFIX)) {
            return encoder.matches(prehash(candidate), stored.substring(PREFIX.length()));
        }
        // Existing HASHED API keys used raw BCrypt and remain usable without rotation.
        // An oversized untrusted candidate still takes one bounded hash comparison,
        // then fails generically instead of throwing or truncating its secret suffix.
        boolean legacyInputFits = candidate.getBytes(StandardCharsets.UTF_8).length <= 72;
        boolean matched = encoder.matches(legacyInputFits ? candidate : prehash(candidate), stored);
        return legacyInputFits && matched;
    }

    private static String prehash(String raw) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(DOMAIN);
            return Base64.getUrlEncoder().withoutPadding().encodeToString(
                    digest.digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
