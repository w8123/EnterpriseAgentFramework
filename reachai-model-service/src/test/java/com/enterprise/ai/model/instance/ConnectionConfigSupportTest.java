package com.enterprise.ai.model.instance;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConnectionConfigSupportTest {

    @Test
    void authorityChangeWithMaskedApiKeyThrows() {
        Map<String, Object> original = Map.of(
                "baseUrl", "https://api.openai.com/v1",
                "apiKey", "sk-live-abcdefgh");
        Map<String, Object> incoming = Map.of(
                "baseUrl", "https://api.deepseek.com",
                "apiKey", "sk-li******efgh");

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> ConnectionConfigSupport.mergeForEdit(original, incoming));
        assertTrue(ex.getMessage().contains("BaseURL authority changed"));
        assertTrue(ex.getMessage().contains("apiKey"));
    }

    @Test
    void sameHostPathChangeKeepsApiKeyViaMergeForEdit() {
        Map<String, Object> original = Map.of(
                "baseUrl", "https://api.openai.com/v1",
                "apiKey", "sk-live-abcdefgh",
                "chatPath", "/chat/completions");
        Map<String, Object> incoming = new LinkedHashMap<>();
        incoming.put("baseUrl", "https://api.openai.com/v1/openai");
        incoming.put("apiKey", "sk-li******efgh");
        incoming.put("chatPath", "/v1/chat/completions");

        Map<String, Object> merged = ConnectionConfigSupport.mergeForEdit(original, incoming);
        assertEquals("sk-live-abcdefgh", merged.get("apiKey"));
        assertEquals("https://api.openai.com/v1/openai", merged.get("baseUrl"));
        assertEquals("/v1/chat/completions", merged.get("chatPath"));
    }

    @Test
    void rejectUserInfoUrl() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> ConnectionConfigSupport.validateBaseUrl("https://user:pass@api.openai.com/v1"));
        assertTrue(ex.getMessage().toLowerCase().contains("userinfo"));
    }

    @Test
    void rejectFtp() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> ConnectionConfigSupport.validateBaseUrl("ftp://files.example.com/models"));
        assertTrue(ex.getMessage().contains("http"));
    }

    @Test
    void authorityKeyNormalizesDefaultPorts() {
        assertEquals(
                ConnectionConfigSupport.authorityKey("https://api.openai.com/v1"),
                ConnectionConfigSupport.authorityKey("https://api.openai.com:443/v1"));
        assertEquals(
                ConnectionConfigSupport.authorityKey("http://localhost/v1"),
                ConnectionConfigSupport.authorityKey("http://localhost:80/v1"));
        assertTrue(ConnectionConfigSupport.sameAuthority(
                "https://API.OpenAI.com/v1",
                "https://api.openai.com:443/v1"));
    }

    @Test
    void stripSecretsRemovesSensitiveKeys() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("baseUrl", "https://api.openai.com/v1");
        map.put("apiKey", "sk-x");
        ConnectionConfigSupport.stripSecrets(map);
        assertFalse(map.containsKey("apiKey"));
        assertEquals("https://api.openai.com/v1", map.get("baseUrl"));
    }

    @Test
    void requireBaseUrlValidatesScheme() {
        assertThrows(IllegalArgumentException.class,
                () -> ConnectionConfigSupport.requireBaseUrl(Map.of("baseUrl", "ftp://x")));
    }
}
