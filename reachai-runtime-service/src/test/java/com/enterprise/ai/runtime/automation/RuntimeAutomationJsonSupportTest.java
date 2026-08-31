package com.enterprise.ai.runtime.automation;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RuntimeAutomationJsonSupportTest {

    private final RuntimeAutomationJsonSupport json = new RuntimeAutomationJsonSupport(new ObjectMapper());

    @Test
    void rejectsCredentialLikeFieldsAtAnyDepth() {
        RuntimeAutomationException failure = assertThrows(
                RuntimeAutomationException.class,
                () -> json.normalizeInput(Map.of(
                        "report", Map.of("recipients", List.of(
                                Map.of("apiToken", "must-not-be-persisted"))))));

        assertEquals("AUTOMATION_INPUT_SECRET_FORBIDDEN", failure.code());
    }

    @Test
    void rejectsUtf8PayloadsAboveTheVersionedInputBudget() {
        RuntimeAutomationException failure = assertThrows(
                RuntimeAutomationException.class,
                () -> json.normalizeInput(Map.of("content", "测".repeat(30_000))));

        assertEquals("AUTOMATION_INPUT_TOO_LARGE", failure.code());
    }

    @Test
    void fingerprintsEquivalentMapsDeterministically() {
        String first = json.fingerprint(Map.of("b", 2, "a", 1));
        String second = json.fingerprint(Map.of("a", 1, "b", 2));

        assertEquals(first, second);
        assertEquals(64, first.length());
    }
}
