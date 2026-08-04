package com.enterprise.ai.control.aicoding.security;

import com.enterprise.ai.control.aicoding.application.AiCodingTaskProperties;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

@Component
public class AiCodingSecretDigester {

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final byte[] pepper;

    public AiCodingSecretDigester(AiCodingTaskProperties properties) {
        String configured = properties.getSecretPepper();
        if (configured == null || configured.length() < 24) {
            throw new IllegalStateException(
                    "reachai.ai-coding-task.secret-pepper must contain at least 24 characters");
        }
        this.pepper = configured.getBytes(StandardCharsets.UTF_8);
    }

    public String generate(String prefix) {
        byte[] bytes = new byte[32];
        SECURE_RANDOM.nextBytes(bytes);
        return prefix + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public String digest(String secret) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalArgumentException("secret is required");
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(pepper, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(secret.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException("HmacSHA256 is not available", ex);
        }
    }

    public boolean matches(String secret, String expectedDigest) {
        if (secret == null || expectedDigest == null) {
            return false;
        }
        try {
            return MessageDigest.isEqual(
                    hex(digest(secret)),
                    hex(expectedDigest));
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }

    private static byte[] hex(String value) {
        try {
            return HexFormat.of().parseHex(value);
        } catch (IllegalArgumentException ex) {
            try {
                return MessageDigest.getInstance("SHA-256")
                        .digest(value.getBytes(StandardCharsets.UTF_8));
            } catch (NoSuchAlgorithmException impossible) {
                throw new IllegalStateException(impossible);
            }
        }
    }
}
