package com.enterprise.ai.control.a2a.infrastructure.security;

import com.enterprise.ai.control.a2a.application.port.A2aCredentialCipher;
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
public class AesGcmA2aCredentialCipher implements A2aCredentialCipher {

    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final A2aHubProperties properties;
    private final SecureRandom secureRandom;

    @Override
    public EncryptedSecret encrypt(byte[] plaintext, String aad) {
        if (plaintext == null || plaintext.length == 0) {
            throw new A2aDomainException("A2A_CREDENTIAL_SECRET_REQUIRED",
                    "outbound credential secret is required");
        }
        KeyMaterial material = requireKey();
        byte[] nonce = new byte[NONCE_BYTES];
        secureRandom.nextBytes(nonce);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, material.key(), new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD(requireAad(aad));
            return new EncryptedSecret(
                    material.keyId(), Base64.getEncoder().encodeToString(nonce),
                    Base64.getEncoder().encodeToString(cipher.doFinal(plaintext)));
        } catch (GeneralSecurityException exception) {
            throw failure("A2A_CREDENTIAL_ENCRYPTION_FAILED",
                    "outbound credential could not be encrypted", exception);
        }
    }

    @Override
    public byte[] decrypt(EncryptedSecret secret, String aad) {
        if (secret == null) {
            throw new A2aDomainException("A2A_CREDENTIAL_SECRET_REQUIRED",
                    "encrypted outbound credential is required");
        }
        KeyMaterial material = requireKey();
        if (!material.keyId().equals(secret.keyId())) {
            throw new A2aDomainException("A2A_CREDENTIAL_KEY_UNAVAILABLE",
                    "the key required to decrypt this outbound credential is unavailable");
        }
        try {
            byte[] nonce = Base64.getDecoder().decode(secret.nonce());
            if (nonce.length != NONCE_BYTES) throw new IllegalArgumentException("invalid nonce");
            byte[] ciphertext = Base64.getDecoder().decode(secret.ciphertext());
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, material.key(), new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD(requireAad(aad));
            return cipher.doFinal(ciphertext);
        } catch (AEADBadTagException exception) {
            throw integrityFailure(exception);
        } catch (GeneralSecurityException | IllegalArgumentException exception) {
            throw integrityFailure(exception);
        }
    }

    private KeyMaterial requireKey() {
        String keyId = properties.getCredentialKeyId() == null
                ? "" : properties.getCredentialKeyId().trim();
        String encoded = properties.getCredentialKeyBase64() == null
                ? "" : properties.getCredentialKeyBase64().trim();
        if (keyId.isEmpty() || keyId.length() > 96 || encoded.isEmpty()) {
            throw new A2aDomainException("A2A_CREDENTIAL_KEY_NOT_CONFIGURED",
                    "A2A outbound credential encryption is not configured");
        }
        try {
            byte[] key = Base64.getDecoder().decode(encoded);
            if (key.length != 32) throw new IllegalArgumentException("AES-256 requires 32 bytes");
            return new KeyMaterial(keyId, new SecretKeySpec(key, "AES"));
        } catch (IllegalArgumentException exception) {
            throw failure("A2A_CREDENTIAL_KEY_INVALID",
                    "A2A credential key must be a Base64-encoded 32-byte value", exception);
        }
    }

    private byte[] requireAad(String aad) {
        if (aad == null || aad.isBlank()) {
            throw new A2aDomainException("A2A_CREDENTIAL_AAD_REQUIRED",
                    "outbound credential encryption requires resource-bound AAD");
        }
        return aad.getBytes(StandardCharsets.UTF_8);
    }

    private A2aDomainException integrityFailure(Exception cause) {
        return failure("A2A_CREDENTIAL_INTEGRITY_FAILED",
                "outbound credential failed integrity verification", cause);
    }

    private A2aDomainException failure(String code, String message, Exception cause) {
        A2aDomainException failure = new A2aDomainException(code, message);
        failure.initCause(cause);
        return failure;
    }

    private record KeyMaterial(String keyId, SecretKeySpec key) {
    }
}
