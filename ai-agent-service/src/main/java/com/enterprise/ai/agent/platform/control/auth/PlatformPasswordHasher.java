package com.enterprise.ai.agent.platform.control.auth;

import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HexFormat;

@Component
public class PlatformPasswordHasher {

    private final SecureRandom random = new SecureRandom();

    public String hash(String rawPassword) {
        byte[] salt = new byte[16];
        random.nextBytes(salt);
        return "sha256:" + HexFormat.of().formatHex(salt) + ":" + digest(salt, rawPassword);
    }

    public boolean matches(String rawPassword, String encoded) {
        if (rawPassword == null || encoded == null || !encoded.startsWith("sha256:")) {
            return false;
        }
        String[] parts = encoded.split(":");
        if (parts.length != 3) {
            return false;
        }
        byte[] salt = HexFormat.of().parseHex(parts[1]);
        return MessageDigest.isEqual(
                digest(salt, rawPassword).getBytes(StandardCharsets.UTF_8),
                parts[2].getBytes(StandardCharsets.UTF_8));
    }

    private String digest(byte[] salt, String rawPassword) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(salt);
            md.update(rawPassword == null ? new byte[0] : rawPassword.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(md.digest());
        } catch (Exception ex) {
            throw new IllegalStateException("password hash failed", ex);
        }
    }
}
