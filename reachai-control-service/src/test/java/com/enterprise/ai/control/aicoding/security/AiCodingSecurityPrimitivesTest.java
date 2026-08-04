package com.enterprise.ai.control.aicoding.security;

import com.enterprise.ai.control.aicoding.application.AiCodingTaskProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiCodingSecurityPrimitivesTest {

    @Test
    void usesSeventyTwoHourCredentialDefaults() {
        AiCodingTaskProperties properties = new AiCodingTaskProperties();

        assertEquals(Duration.ofHours(72), properties.getActivationTtl());
        assertEquals(Duration.ofHours(72), properties.getTokenTtl());
    }

    @Test
    void hashesGeneratedSecretsWithPepperAndConstantTimeComparison() {
        AiCodingTaskProperties properties = new AiCodingTaskProperties();
        properties.setSecretPepper(
                "test-only-reachai-ai-coding-pepper");
        AiCodingSecretDigester digester =
                new AiCodingSecretDigester(properties);

        String secret = digester.generate("rtt_");
        String digest = digester.digest(secret);

        assertTrue(secret.startsWith("rtt_"));
        assertNotEquals(secret, digest);
        assertTrue(digester.matches(secret, digest));
        assertFalse(digester.matches(secret + "x", digest));
    }

    @Test
    void refusesWeakPepper() {
        AiCodingTaskProperties properties = new AiCodingTaskProperties();
        properties.setSecretPepper("too-short");
        assertThrows(
                IllegalStateException.class,
                () -> new AiCodingSecretDigester(properties));
    }

    @Test
    void recursivelyRemovesSensitiveFieldsWithoutMutatingSource()
            throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        JsonNode source = mapper.readTree("""
                {
                  "projectCode":"orders",
                  "registry_app_secret":"secret",
                  "nested":{
                    "activationCode":"code",
                    "safe":"visible",
                    "embedToken":"embed-token",
                    "providerSecret":"provider-secret",
                    "apiKey":"api-key",
                    "command":"call with Bearer abcdefghijklmnop and rtt_abcdefghijk"
                  },
                  "items":[{"authorization":"Bearer token","name":"one"}]
                }
                """);

        JsonNode sanitized =
                new AiCodingSensitiveJsonSanitizer().sanitize(source);

        assertEquals("secret", source.path("registry_app_secret").asText());
        assertFalse(sanitized.has("registry_app_secret"));
        assertFalse(sanitized.path("nested").has("activationCode"));
        assertFalse(sanitized.path("nested").has("embedToken"));
        assertFalse(sanitized.path("nested").has("providerSecret"));
        assertFalse(sanitized.path("nested").has("apiKey"));
        assertEquals(
                "call with Bearer [REDACTED] and [REDACTED]",
                sanitized.path("nested").path("command").asText());
        assertEquals("visible", sanitized.path("nested").path("safe").asText());
        assertFalse(sanitized.path("items").get(0).has("authorization"));
        assertEquals("one", sanitized.path("items").get(0).path("name").asText());
    }
}
