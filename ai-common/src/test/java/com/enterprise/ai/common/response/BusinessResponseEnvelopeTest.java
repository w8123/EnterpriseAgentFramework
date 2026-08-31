package com.enterprise.ai.common.response;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BusinessResponseEnvelopeTest {

    @Test
    void acceptsNumericAndStringBusinessCode200() {
        assertTrue(BusinessResponseEnvelope.failure(Map.of("code", 200, "success", true)).isEmpty());
        assertTrue(BusinessResponseEnvelope.failure(Map.of("code", "200", "success", true)).isEmpty());
    }

    @Test
    void rejectsNon200BusinessCodeWithClearQueryFailureMessage() {
        BusinessResponseEnvelope.Failure failure = BusinessResponseEnvelope.failure(Map.of(
                        "code", "500",
                        "success", false,
                        "message", "无权查看班组"))
                .orElseThrow();

        assertEquals("500", failure.businessCode());
        assertEquals("查询失败：无权查看班组", failure.message());
    }

    @Test
    void rejectsExplicitSuccessFalseEvenWithoutCode() {
        BusinessResponseEnvelope.Failure failure = BusinessResponseEnvelope.failure(Map.of("success", false))
                .orElseThrow();

        assertEquals("查询失败", failure.message());
    }

    @Test
    void leavesOrdinaryPayloadWithoutEnvelopeUntouched() {
        assertTrue(BusinessResponseEnvelope.failure(Map.of("orderStatus", "PAID")).isEmpty());
    }

    @Test
    void returnsEmptyForNullAndNonMapPayloads() {
        assertTrue(BusinessResponseEnvelope.failure(null).isEmpty());
        assertTrue(BusinessResponseEnvelope.failure("text").isEmpty());
        assertTrue(BusinessResponseEnvelope.failure(List.of("a", "b")).isEmpty());
    }

    @Test
    void acceptsTrimmedDecimalAndNumericRepresentationsOf200() {
        assertTrue(BusinessResponseEnvelope.failure(Map.of("code", 200L, "success", true)).isEmpty());
        assertTrue(BusinessResponseEnvelope.failure(Map.of("code", new BigDecimal("200.00"), "success", true)).isEmpty());
        assertTrue(BusinessResponseEnvelope.failure(Map.of("code", " 200.00 ", "success", true)).isEmpty());
    }

    @Test
    void treatsPresentNullCodeAsFailure() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", null);

        BusinessResponseEnvelope.Failure failure = BusinessResponseEnvelope.failure(body).orElseThrow();

        assertNull(failure.businessCode());
        assertEquals("查询失败", failure.message());
    }

    @Test
    void treatsBlankAndNonNumericCodesAsFailures() {
        Map<String, Object> blank = new LinkedHashMap<>();
        blank.put("code", "   ");

        BusinessResponseEnvelope.Failure blankFailure = BusinessResponseEnvelope.failure(blank).orElseThrow();
        assertNull(blankFailure.businessCode());

        BusinessResponseEnvelope.Failure nonNumeric = BusinessResponseEnvelope.failure(Map.of("code", " ERR_CODE ")).orElseThrow();
        assertEquals("ERR_CODE", nonNumeric.businessCode());
    }

    @Test
    void non200CodeFailsEvenWhenSuccessIsTrue() {
        BusinessResponseEnvelope.Failure failure = BusinessResponseEnvelope.failure(Map.of("code", 500, "success", true)).orElseThrow();

        assertEquals("500", failure.businessCode());
        assertEquals("查询失败", failure.message());
    }

    @Test
    void explicitFalseFailsEvenWhenCodeIs200() {
        BusinessResponseEnvelope.Failure failure = BusinessResponseEnvelope.failure(Map.of("code", 200, "success", false)).orElseThrow();

        assertEquals("200", failure.businessCode());
        assertEquals("查询失败", failure.message());
    }

    @Test
    void parsesTrimmedCaseInsensitiveBooleanStrings() {
        assertTrue(BusinessResponseEnvelope.failure(Map.of("code", 200, "success", " TrUe ")).isEmpty());

        BusinessResponseEnvelope.Failure failure = BusinessResponseEnvelope.failure(Map.of("success", " FaLsE ")).orElseThrow();
        assertEquals("查询失败", failure.message());
    }

    @Test
    void selectsFirstNonBlankMessageThenMsgThenError() {
        assertEquals("查询失败：A",
                BusinessResponseEnvelope.failure(Map.of("success", false, "message", "A", "msg", "B", "error", "C")).orElseThrow().message());
        assertEquals("查询失败：B",
                BusinessResponseEnvelope.failure(Map.of("success", false, "message", "  ", "msg", "B")).orElseThrow().message());
        assertEquals("查询失败：C",
                BusinessResponseEnvelope.failure(Map.of("success", false, "error", "C")).orElseThrow().message());
    }

    @Test
    void keepsGenericAndAlreadyPrefixedFailureMessagesStable() {
        assertEquals("查询失败", BusinessResponseEnvelope.failure(Map.of("success", false, "message", "查询失败")).orElseThrow().message());
        assertEquals("查询失败：详情", BusinessResponseEnvelope.failure(Map.of("success", false, "message", "查询失败：详情")).orElseThrow().message());
        assertEquals("查询失败:详情", BusinessResponseEnvelope.failure(Map.of("success", false, "message", "查询失败:详情")).orElseThrow().message());
    }

    @Test
    void sanitizesCrLfInFailureDetail() {
        String message = BusinessResponseEnvelope.failure(Map.of("success", false, "message", "a\rb\nc")).orElseThrow().message();

        assertEquals("查询失败：a b c", message);
        assertFalse(message.contains("\r"));
        assertFalse(message.contains("\n"));
    }

    @Test
    void truncatesFailureDetailTo240CharactersBeforePrefix() {
        String message = BusinessResponseEnvelope.failure(Map.of("success", false, "message", "x".repeat(241))).orElseThrow().message();

        assertEquals("查询失败：" + "x".repeat(240), message);
    }

    @Test
    void sanitizesAndTruncatesBusinessCodeText() {
        BusinessResponseEnvelope.Failure sanitized = BusinessResponseEnvelope.failure(Map.of("code", "A\rB")).orElseThrow();
        assertEquals("A B", sanitized.businessCode());

        BusinessResponseEnvelope.Failure truncated = BusinessResponseEnvelope.failure(Map.of("code", "y".repeat(241))).orElseThrow();
        assertEquals("y".repeat(240), truncated.businessCode());
    }
}
