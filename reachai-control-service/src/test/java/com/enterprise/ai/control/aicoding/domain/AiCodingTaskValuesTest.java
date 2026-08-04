package com.enterprise.ai.control.aicoding.domain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AiCodingTaskValuesTest {

    @Test
    void countsUnicodeCodePointsForCharacterLimits() {
        assertEquals(
                "😀😀",
                AiCodingTaskValues.requiredText(
                        " 😀😀 ",
                        "value",
                        2));
        assertThrows(
                IllegalArgumentException.class,
                () -> AiCodingTaskValues.requiredText(
                        "😀😀",
                        "value",
                        1));
        assertEquals(
                "a…",
                AiCodingTaskValues.fitText("a😀b", 2));
    }

    @Test
    void enforcesTextStorageLimitsUsingUtf8Bytes() {
        assertEquals(
                "严".repeat(20_000),
                AiCodingTaskValues.requiredTextUtf8(
                        "严".repeat(20_000),
                        "value",
                        AiCodingTaskValues.TEXT_MAX_UTF8_BYTES));
        assertThrows(
                IllegalArgumentException.class,
                () -> AiCodingTaskValues.requiredTextUtf8(
                        "严".repeat(20_001),
                        "value",
                        AiCodingTaskValues.TEXT_MAX_UTF8_BYTES));
    }
}
