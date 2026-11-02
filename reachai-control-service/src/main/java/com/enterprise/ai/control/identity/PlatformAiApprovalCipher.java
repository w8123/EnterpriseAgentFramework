package com.enterprise.ai.control.identity;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/** Encrypts review payloads and uses a separate derived key for deduplication fingerprints. */
@Component
public final class PlatformAiApprovalCipher {
    private static final int NONCE_BYTES = 12;
    private final String keyId;
    private final String keyBase64;
    private final SecureRandom random = new SecureRandom();

    public PlatformAiApprovalCipher(@Value("${REACHAI_PLATFORM_AI_APPROVAL_KEY_ID:${reachai.platform-ai.approval-key-id:}}") String keyId,
                                    @Value("${REACHAI_PLATFORM_AI_APPROVAL_KEY_BASE64:${reachai.platform-ai.approval-key-base64:}}") String keyBase64) {
        this.keyId = keyId;
        this.keyBase64 = keyBase64;
    }

    public Encrypted encrypt(byte[] plaintext, String aad) {
        byte[] key = key();
        byte[] nonce = new byte[NONCE_BYTES];
        random.nextBytes(nonce);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
            cipher.updateAAD(aad.getBytes(StandardCharsets.UTF_8));
            return new Encrypted(keyId.trim(), Base64.getEncoder().encodeToString(nonce),
                    Base64.getEncoder().encodeToString(cipher.doFinal(plaintext)));
        } catch (GeneralSecurityException failure) {
            throw unavailable("approval encryption failed");
        }
    }

    public byte[] decrypt(Encrypted encrypted, String aad) {
        byte[] key = key();
        if (encrypted == null || !keyId.trim().equals(encrypted.keyId())
                || encrypted.nonce() == null || encrypted.ciphertext() == null) {
            throw unavailable("approval key or payload is unavailable");
        }
        try {
            byte[] nonce = Base64.getDecoder().decode(encrypted.nonce());
            if (nonce.length != NONCE_BYTES) throw new IllegalArgumentException("invalid nonce");
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
            cipher.updateAAD(aad.getBytes(StandardCharsets.UTF_8));
            return cipher.doFinal(Base64.getDecoder().decode(encrypted.ciphertext()));
        } catch (GeneralSecurityException | IllegalArgumentException failure) {
            throw unavailable("approval payload integrity check failed");
        }
    }

    public String fingerprint(String context, byte[] plaintext) {
        try {
            Mac derivation = Mac.getInstance("HmacSHA256");
            derivation.init(new SecretKeySpec(key(), "HmacSHA256"));
            byte[] derived = derivation.doFinal("reachai-platform-approval-fingerprint-v1".getBytes(StandardCharsets.UTF_8));
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(derived, "HmacSHA256"));
            mac.update(context.getBytes(StandardCharsets.UTF_8));
            mac.update((byte) 0);
            return HexFormat.of().formatHex(mac.doFinal(plaintext));
        } catch (GeneralSecurityException failure) {
            throw unavailable("approval fingerprint failed");
        }
    }

    private byte[] key() {
        if (keyId == null || keyId.isBlank() || keyId.length() > 96 || keyBase64 == null || keyBase64.isBlank()) {
            throw unavailable("platform AI approval encryption is not configured");
        }
        try {
            byte[] decoded = Base64.getDecoder().decode(keyBase64.trim());
            if (decoded.length != 32) throw new IllegalArgumentException("invalid key length");
            return decoded;
        } catch (IllegalArgumentException invalid) {
            throw unavailable("platform AI approval encryption key is invalid");
        }
    }

    private ResponseStatusException unavailable(String message) {
        return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, message);
    }

    public record Encrypted(String keyId, String nonce, String ciphertext) { }
}
