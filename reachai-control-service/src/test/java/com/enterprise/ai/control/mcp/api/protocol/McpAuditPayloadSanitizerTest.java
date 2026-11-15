package com.enterprise.ai.control.mcp.api.protocol;

import com.enterprise.ai.control.mcp.infrastructure.McpHubProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class McpAuditPayloadSanitizerTest {
    @Test
    void enabledPayloadCaptureRedactsTaskCodesAndTokensInNestedFieldsAndText() throws Exception {
        var properties = new McpHubProperties();
        properties.setCaptureAuditPayload(true);
        var json = new ObjectMapper();
        var sanitizer = new McpAuditPayloadSanitizer(json, properties);
        String captured = sanitizer.capture(Map.of("params", Map.of("arguments", Map.of(
                "claimCode", "fixture-claim", "activation_code", "fixture-activation",
                "api_key", "fixture-key", "notes", List.of("code rhc_abcdefghijk", "safe")))));
        assertNotNull(captured);
        assertFalse(captured.contains("fixture-claim"));
        assertFalse(captured.contains("fixture-activation"));
        assertFalse(captured.contains("fixture-key"));
        assertFalse(captured.contains("rhc_abcdefghijk"));
        assertTrue(captured.contains("safe"));
        assertEquals("[redacted]", json.readTree(captured).path("params").path("arguments").path("claimCode").asText());
    }
}
