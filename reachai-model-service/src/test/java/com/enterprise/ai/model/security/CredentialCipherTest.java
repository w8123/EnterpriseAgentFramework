package com.enterprise.ai.model.security;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CredentialCipherTest {

    private final CredentialCipher cipher = new CredentialCipher("unit-test-secret-for-model-center");

    @Test
    void rejectsMissingEncryptionSecret() {
        assertThrows(IllegalStateException.class, () -> new CredentialCipher(" "));
    }

    @Test
    void encryptDecryptRoundTrip() {
        String plain = "{\"baseUrl\":\"https://api.example.com\",\"apiKey\":\"sk-secret\"}";
        String encrypted = cipher.encrypt(plain);
        assertTrue(encrypted.startsWith("aesgcm:"));
        assertEquals(plain, cipher.decrypt(encrypted));
    }

    @Test
    void decryptBlankReturnsEmptyObject() {
        assertEquals("{}", cipher.decrypt(""));
        assertEquals("{}", cipher.decrypt(null));
        assertEquals("{}", cipher.decrypt("   "));
    }

    @Test
    void decryptRejectsPlaintext() {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> cipher.decrypt("{\"apiKey\":\"plain\"}"));
        assertTrue(ex.getMessage().contains("aesgcm"));
    }
}
