package com.enterprise.ai.control.a2a.infrastructure.security;

import com.enterprise.ai.control.a2a.domain.A2aDomainException;
import com.enterprise.ai.control.a2a.infrastructure.A2aHubProperties;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AesGcmA2aCredentialCipherTest {

    @Test
    void roundTripsWithResourceBoundAadAndRejectsWrongBinding() {
        A2aHubProperties properties = configuredProperties();
        AesGcmA2aCredentialCipher cipher = new AesGcmA2aCredentialCipher(
                properties, new SecureRandom());
        byte[] plaintext = "partner-secret-value".getBytes(StandardCharsets.UTF_8);

        var encrypted = cipher.encrypt(plaintext, "reachai:a2a:credential:outbound:partner:1");

        assertNotEquals(Base64.getEncoder().encodeToString(plaintext), encrypted.ciphertext());
        assertArrayEquals(plaintext, cipher.decrypt(encrypted,
                "reachai:a2a:credential:outbound:partner:1"));
        A2aDomainException failure = assertThrows(A2aDomainException.class,
                () -> cipher.decrypt(encrypted,
                        "reachai:a2a:credential:outbound:other:1"));
        assertEquals("A2A_CREDENTIAL_INTEGRITY_FAILED", failure.code());
    }

    @Test
    void failsClosedWhenTheDedicatedCredentialKeyIsMissing() {
        AesGcmA2aCredentialCipher cipher = new AesGcmA2aCredentialCipher(
                new A2aHubProperties(), new SecureRandom());
        A2aDomainException failure = assertThrows(A2aDomainException.class,
                () -> cipher.encrypt(new byte[]{1}, "resource"));
        assertEquals("A2A_CREDENTIAL_KEY_NOT_CONFIGURED", failure.code());
    }

    private A2aHubProperties configuredProperties() {
        A2aHubProperties properties = new A2aHubProperties();
        properties.setCredentialKeyId("credential-key-v1");
        properties.setCredentialKeyBase64(Base64.getEncoder().encodeToString(new byte[32]));
        return properties;
    }
}
