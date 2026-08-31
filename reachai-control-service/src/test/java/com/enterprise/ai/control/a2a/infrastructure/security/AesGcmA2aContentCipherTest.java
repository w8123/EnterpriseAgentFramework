package com.enterprise.ai.control.a2a.infrastructure.security;

import com.enterprise.ai.control.a2a.application.port.A2aContentCipher.EncryptedContent;
import com.enterprise.ai.control.a2a.domain.A2aDomainException;
import com.enterprise.ai.control.a2a.infrastructure.A2aHubProperties;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AesGcmA2aContentCipherTest {

    @Test
    void encryptsWithFreshNonceAndBindsCiphertextToResourceAad() {
        A2aHubProperties properties = configuredProperties();
        AesGcmA2aContentCipher cipher = new AesGcmA2aContentCipher(properties, new SecureRandom());
        byte[] plaintext = "enterprise message".getBytes(StandardCharsets.UTF_8);

        EncryptedContent first = cipher.encrypt(plaintext, "message|principal-1|m-1");
        EncryptedContent second = cipher.encrypt(plaintext, "message|principal-1|m-1");

        assertEquals("a2a-test-key", first.keyId());
        assertNotEquals(first.nonce(), second.nonce());
        assertNotEquals(first.ciphertext(), second.ciphertext());
        assertFalse(first.ciphertext().contains("enterprise message"));
        assertArrayEquals(plaintext, cipher.decrypt(first, "message|principal-1|m-1"));

        A2aDomainException error = assertThrows(A2aDomainException.class,
                () -> cipher.decrypt(first, "message|principal-2|m-1"));
        assertEquals("A2A_CONTENT_INTEGRITY_FAILED", error.code());
    }

    @Test
    void failsClosedWhenNoRuntimeKeyIsConfigured() {
        A2aHubProperties properties = new A2aHubProperties();
        AesGcmA2aContentCipher cipher = new AesGcmA2aContentCipher(properties, new SecureRandom());

        A2aDomainException error = assertThrows(A2aDomainException.class,
                () -> cipher.encrypt(new byte[]{1}, "resource"));

        assertEquals("A2A_CONTENT_KEY_NOT_CONFIGURED", error.code());
    }

    private A2aHubProperties configuredProperties() {
        A2aHubProperties properties = new A2aHubProperties();
        properties.setContentKeyId("a2a-test-key");
        properties.setContentKeyBase64(Base64.getEncoder().encodeToString(new byte[32]));
        return properties;
    }
}
