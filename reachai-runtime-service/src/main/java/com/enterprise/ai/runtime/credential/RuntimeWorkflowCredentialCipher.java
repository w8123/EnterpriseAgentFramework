package com.enterprise.ai.runtime.credential;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

@Slf4j
@Component
public class RuntimeWorkflowCredentialCipher {

    private static final String PREFIX = "aesgcm:";
    static final String DEVELOPMENT_DEFAULT_SECRET = "dev-only-change-me-please-32-bytes";
    private static final int IV_LENGTH = 12;
    private static final int TAG_LENGTH_BITS = 128;

    private final SecureRandom secureRandom = new SecureRandom();
    private final SecretKeySpec keySpec;

    public RuntimeWorkflowCredentialCipher(String secret) {
        this(secret, false, "");
    }

    @Autowired
    RuntimeWorkflowCredentialCipher(
                                    @Value("${agent.workflow-credential-secret:" + DEVELOPMENT_DEFAULT_SECRET + "}")
                                    String secret,
                                    @Value("${reachai.security.workflow-credential-secret.require-non-default:false}")
                                    boolean requireNonDefaultSecret,
                                    @Value("${spring.profiles.active:}") String activeProfiles) {
        this.keySpec = new SecretKeySpec(sha256(secret), "AES");
        if (usesInsecureSecret(secret)) {
            if (requireNonDefaultSecret || hasProductionProfile(activeProfiles)) {
                throw new IllegalStateException("agent.workflow-credential-secret must be configured with a non-default value in production");
            }
            log.warn("agent.workflow-credential-secret is using a development default. "
                    + "Set WORKFLOW_CREDENTIAL_SECRET before production deployment.");
        }
    }

    public String encrypt(String plainText) {
        if (plainText == null || plainText.isBlank()) {
            return "";
        }
        try {
            byte[] iv = new byte[IV_LENGTH];
            secureRandom.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, keySpec, new GCMParameterSpec(TAG_LENGTH_BITS, iv));
            byte[] cipherText = cipher.doFinal(plainText.getBytes(StandardCharsets.UTF_8));
            byte[] payload = new byte[iv.length + cipherText.length];
            System.arraycopy(iv, 0, payload, 0, iv.length);
            System.arraycopy(cipherText, 0, payload, iv.length, cipherText.length);
            return PREFIX + Base64.getEncoder().encodeToString(payload);
        } catch (Exception ex) {
            throw new IllegalStateException("Encrypt workflow credential failed", ex);
        }
    }

    public String decrypt(String encryptedText) {
        if (encryptedText == null || encryptedText.isBlank()) {
            return "{}";
        }
        if (!encryptedText.startsWith(PREFIX)) {
            return encryptedText;
        }
        try {
            byte[] payload = Base64.getDecoder().decode(encryptedText.substring(PREFIX.length()));
            byte[] iv = new byte[IV_LENGTH];
            byte[] cipherText = new byte[payload.length - IV_LENGTH];
            System.arraycopy(payload, 0, iv, 0, IV_LENGTH);
            System.arraycopy(payload, IV_LENGTH, cipherText, 0, cipherText.length);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, keySpec, new GCMParameterSpec(TAG_LENGTH_BITS, iv));
            return new String(cipher.doFinal(cipherText), StandardCharsets.UTF_8);
        } catch (Exception ex) {
            throw new IllegalStateException("Decrypt workflow credential failed", ex);
        }
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest((value == null ? "" : value).getBytes(StandardCharsets.UTF_8));
        } catch (Exception ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }

    private static boolean usesInsecureSecret(String value) {
        String normalized = value == null ? "" : value.trim();
        return normalized.isEmpty()
                || normalized.startsWith("dev-only-change-me");
    }

    private static boolean hasProductionProfile(String activeProfiles) {
        if (activeProfiles == null || activeProfiles.isBlank()) {
            return false;
        }
        for (String profile : activeProfiles.split(",")) {
            String normalized = profile.trim().toLowerCase();
            if ("prod".equals(normalized) || "production".equals(normalized)) {
                return true;
            }
        }
        return false;
    }
}
