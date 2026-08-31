package com.enterprise.ai.control.a2a.infrastructure.security;

import com.enterprise.ai.control.a2a.application.port.A2aContentCipher;
import com.enterprise.ai.control.a2a.domain.A2aDomainException;
import com.enterprise.ai.control.a2a.infrastructure.A2aHubProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;

@Component
@RequiredArgsConstructor
public class AesGcmA2aContentCipher implements A2aContentCipher {

    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final A2aHubProperties properties;
    private final SecureRandom secureRandom;

    @Override
    public EncryptedContent encrypt(byte[] plaintext, String aad) {
        if (plaintext == null) {
            throw new A2aDomainException("A2A_CONTENT_REQUIRED", "content is required");
        }
        KeyMaterial material = requireKey();
        byte[] nonce = new byte[NONCE_BYTES];
        secureRandom.nextBytes(nonce);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, material.key(), new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD(requireAad(aad));
            return new EncryptedContent(material.keyId(),
                    Base64.getEncoder().encodeToString(nonce),
                    Base64.getEncoder().encodeToString(cipher.doFinal(plaintext)));
        } catch (GeneralSecurityException exception) {
            throw encryptionFailure(exception);
        }
    }

    @Override
    public byte[] decrypt(EncryptedContent content, String aad) {
        if (content == null) {
            throw new A2aDomainException("A2A_CONTENT_REQUIRED", "encrypted content is required");
        }
        KeyMaterial material = requireKey();
        if (!material.keyId().equals(content.keyId())) {
            throw new A2aDomainException("A2A_CONTENT_KEY_UNAVAILABLE",
                    "the key required to decrypt this A2A resource is unavailable");
        }
        try {
            byte[] nonce = Base64.getDecoder().decode(content.nonce());
            if (nonce.length != NONCE_BYTES) {
                throw new IllegalArgumentException("invalid nonce length");
            }
            byte[] ciphertext = Base64.getDecoder().decode(content.ciphertext());
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, material.key(), new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD(requireAad(aad));
            return cipher.doFinal(ciphertext);
        } catch (AEADBadTagException exception) {
            throw contentIntegrityFailure(exception);
        } catch (GeneralSecurityException | IllegalArgumentException exception) {
            throw contentIntegrityFailure(exception);
        }
    }

    private KeyMaterial requireKey() {
        String keyId = properties.getContentKeyId() == null ? "" : properties.getContentKeyId().trim();
        String encoded = properties.getContentKeyBase64() == null
                ? "" : properties.getContentKeyBase64().trim();
        if (keyId.isEmpty() || keyId.length() > 96 || encoded.isEmpty()) {
            throw new A2aDomainException("A2A_CONTENT_KEY_NOT_CONFIGURED",
                    "A2A content encryption is not configured");
        }
        try {
            byte[] key = Base64.getDecoder().decode(encoded);
            if (key.length != 32) {
                throw new IllegalArgumentException("AES-256 requires 32 bytes");
            }
            return new KeyMaterial(keyId, new SecretKeySpec(key, "AES"));
        } catch (IllegalArgumentException exception) {
            A2aDomainException error = new A2aDomainException("A2A_CONTENT_KEY_INVALID",
                    "A2A content encryption key must be a Base64-encoded 32-byte value");
            error.initCause(exception);
            throw error;
        }
    }

    private byte[] requireAad(String aad) {
        if (aad == null || aad.isBlank()) {
            throw new A2aDomainException("A2A_CONTENT_AAD_REQUIRED",
                    "A2A content encryption requires a resource-bound AAD");
        }
        return aad.getBytes(StandardCharsets.UTF_8);
    }

    private A2aDomainException encryptionFailure(Exception cause) {
        A2aDomainException error = new A2aDomainException("A2A_CONTENT_ENCRYPTION_FAILED",
                "A2A content could not be encrypted");
        error.initCause(cause);
        return error;
    }

    private A2aDomainException contentIntegrityFailure(Exception cause) {
        A2aDomainException error = new A2aDomainException("A2A_CONTENT_INTEGRITY_FAILED",
                "A2A content failed integrity verification");
        error.initCause(cause);
        return error;
    }

    private record KeyMaterial(String keyId, SecretKeySpec key) {
    }
}
