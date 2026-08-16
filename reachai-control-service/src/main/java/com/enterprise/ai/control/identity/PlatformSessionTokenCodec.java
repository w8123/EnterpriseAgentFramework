package com.enterprise.ai.control.identity;

import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;

/** Keeps raw browser tokens out of the database while retaining opaque Bearer semantics. */
@Component
public class PlatformSessionTokenCodec {

    private final SecureRandom secureRandom = new SecureRandom();

    public String issueRawToken() {
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        return "pat_" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public String digest(String rawToken) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(rawToken.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(digest.length * 2);
            for (byte value : digest) {
                result.append(Character.forDigit((value >>> 4) & 0xf, 16));
                result.append(Character.forDigit(value & 0xf, 16));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required for platform session tokens", exception);
        }
    }
}
