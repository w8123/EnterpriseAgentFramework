package com.enterprise.ai.agent.tools.dynamic;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DynamicHttpToolBaseUrlSupportTest {

    @Test
    void normalizesCommonMissingSlashesAfterHttpScheme() {
        assertEquals("http://127.0.0.1:8611",
                DynamicHttpToolBaseUrlSupport.normalizeHttpBaseUrl("http:127.0.0.1:8611"));
        assertEquals("https://api.example.com",
                DynamicHttpToolBaseUrlSupport.normalizeHttpBaseUrl(" https:api.example.com "));
    }

    @Test
    void validatesHostAfterNormalization() {
        assertTrue(DynamicHttpToolBaseUrlSupport.isValidRestClientBaseUrl(
                DynamicHttpToolBaseUrlSupport.normalizeHttpBaseUrl("http:127.0.0.1:8611")));
        assertFalse(DynamicHttpToolBaseUrlSupport.isValidRestClientBaseUrl("http://"));
        assertFalse(DynamicHttpToolBaseUrlSupport.isValidRestClientBaseUrl("/api/orders"));
    }
}
