package com.enterprise.ai.control.mcp.application.platform;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Keeps connection secrets out of frozen approvals. A new secret becomes a salted SHA-256 fingerprint; the executing
 * connection presents the same value again, and masked values read back from an owner keep the stored secret.
 */
final class PlatformConnectionSecrets {
    static final String MASK = "******";
    private static final SecureRandom RANDOM = new SecureRandom();

    private PlatformConnectionSecrets() { }

    static boolean sensitive(String key) {
        String k = key.toLowerCase(Locale.ROOT);
        return k.contains("key") || k.contains("secret") || k.contains("token") || k.contains("password");
    }

    static Object fingerprinted(Object connection) {
        if (!(connection instanceof Map<?, ?> values)) return connection;
        Map<String, Object> result = new LinkedHashMap<>();
        values.forEach((key, value) -> {
            String name = String.valueOf(key);
            if (value instanceof String text && sensitive(name) && !text.contains(MASK)) {
                byte[] salt = new byte[16];
                RANDOM.nextBytes(salt);
                String saltHex = HexFormat.of().formatHex(salt);
                result.put(name, Map.of("secretSalt", saltHex, "secretSha256", fingerprint(saltHex, text)));
            } else {
                result.put(name, value);
            }
        });
        return result;
    }

    /** A frozen approval never carries a plain secret; one that does was not produced by these services. */
    static void verifyFingerprints(Object connection) {
        if (!(connection instanceof Map<?, ?> values)) return;
        values.forEach((key, value) -> {
            if (sensitive(String.valueOf(key)) && value instanceof String text && !text.contains(MASK)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT);
            }
        });
    }

    /** Replaces each fingerprint with the presented secret; a missing, mismatched or extra secret refuses the call. */
    static Object withSecrets(Object connection, Map<String, Object> secrets) {
        if (!(connection instanceof Map<?, ?> values)) {
            if (!secrets.isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
            return connection;
        }
        Map<String, Object> resolved = new LinkedHashMap<>();
        int used = 0;
        for (var entry : values.entrySet()) {
            String key = String.valueOf(entry.getKey());
            if (entry.getValue() instanceof Map<?, ?> print && print.containsKey("secretSha256")) {
                if (!(secrets.get(key) instanceof String secret) || !matches(String.valueOf(print.get("secretSalt")),
                        String.valueOf(print.get("secretSha256")), secret)) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "secret does not match the approved fingerprint");
                }
                resolved.put(key, secret);
                used++;
            } else {
                resolved.put(key, entry.getValue());
            }
        }
        if (used != secrets.size()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        return resolved;
    }

    /** Applies the owner's masking rule to anything returned or frozen, in case a value slipped through unmasked. */
    static Object masked(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            map.forEach((key, item) -> result.put(String.valueOf(key),
                    item instanceof String text && sensitive(String.valueOf(key)) && !text.contains(MASK) ? MASK : masked(item)));
            return result;
        }
        if (value instanceof List<?> list) return list.stream().map(PlatformConnectionSecrets::masked).toList();
        return value;
    }

    static boolean matches(String salt, String expectedSha256, String secret) {
        return MessageDigest.isEqual(fingerprint(salt, secret).getBytes(StandardCharsets.US_ASCII),
                expectedSha256.getBytes(StandardCharsets.US_ASCII));
    }

    static String fingerprint(String salt, String secret) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            digest.update(salt.getBytes(StandardCharsets.US_ASCII));
            return HexFormat.of().formatHex(digest.digest(secret.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
