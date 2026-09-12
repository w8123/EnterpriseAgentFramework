package com.enterprise.ai.runtime.memory;

import com.enterprise.ai.runtime.configuration.RuntimeContextEngineeringProperties;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.HexFormat;

/** AES-256-GCM envelope encryption with scope-bound additional authenticated data. */
@Component
public class RuntimeToolResultArtifactCipher {

    private static final int GCM_TAG_BITS = 128;
    private static final int NONCE_BYTES = 12;

    private final SecureRandom secureRandom = new SecureRandom();
    private final SecretKeySpec encryptionKey;
    private final SecretKeySpec fingerprintKey;
    private final String keyId;

    public RuntimeToolResultArtifactCipher(RuntimeContextEngineeringProperties properties) {
        String secret = properties.artifactEncryptionSecret();
        if (properties.toolResultArtifactStoreConfigured() && secret.length() < 32) {
            throw new IllegalStateException(
                    "REACHAI_RUNTIME_CONTEXT_ARTIFACT_SECRET must contain at least 32 characters "
                            + "when Runtime tool-result offload is enabled");
        }
        this.encryptionKey = secret.isBlank()
                ? null
                : new SecretKeySpec(
                        sha256("reachai-runtime-tool-result-encryption-v1\n" + secret), "AES");
        this.fingerprintKey = secret.isBlank()
                ? null
                : new SecretKeySpec(
                        sha256("reachai-runtime-tool-result-fingerprint-v1\n" + secret),
                        "HmacSHA256");
        this.keyId = properties.artifactEncryptionKeyId();
    }

    public EncryptedPayload encrypt(byte[] plaintext, String additionalAuthenticatedData) {
        requireConfigured();
        byte[] nonce = new byte[NONCE_BYTES];
        secureRandom.nextBytes(nonce);
        return new EncryptedPayload(
                keyId,
                nonce,
                crypt(Cipher.ENCRYPT_MODE, plaintext, nonce, additionalAuthenticatedData));
    }

    public byte[] decrypt(
            byte[] ciphertext,
            byte[] nonce,
            String encryptionKeyId,
            String additionalAuthenticatedData) {
        requireConfigured();
        if (!keyId.equals(encryptionKeyId)) {
            throw new IllegalStateException("tool-result artifact encryption key is unavailable");
        }
        if (nonce == null || nonce.length != NONCE_BYTES || ciphertext == null) {
            throw new IllegalStateException("tool-result artifact ciphertext is incomplete");
        }
        return crypt(Cipher.DECRYPT_MODE, ciphertext, nonce, additionalAuthenticatedData);
    }

    /** Keyed content fingerprint for active-row dedupe without exposing a raw plaintext hash. */
    public String fingerprint(byte[] plaintext) {
        requireConfigured();
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(fingerprintKey);
            return HexFormat.of().formatHex(mac.doFinal(plaintext));
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("tool-result artifact fingerprint failed", ex);
        }
    }

    private byte[] crypt(int mode, byte[] input, byte[] nonce, String aad) {
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(mode, encryptionKey, new GCMParameterSpec(GCM_TAG_BITS, nonce));
            cipher.updateAAD(aad.getBytes(StandardCharsets.UTF_8));
            return cipher.doFinal(input);
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("tool-result artifact encryption operation failed", ex);
        }
    }

    private void requireConfigured() {
        if (encryptionKey == null || fingerprintKey == null) {
            throw new IllegalStateException("tool-result artifact encryption is not configured");
        }
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    public record EncryptedPayload(String keyId, byte[] nonce, byte[] ciphertext) {
    }
}
