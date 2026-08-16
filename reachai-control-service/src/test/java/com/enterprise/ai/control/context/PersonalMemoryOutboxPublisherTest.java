package com.enterprise.ai.control.context;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PersonalMemoryOutboxPublisherTest {

    @Test
    void persistedFailureCodeNeverContainsTheExceptionMessage() {
        String secret = "memory body and bearer credential must never be persisted";

        String code = PersonalMemoryOutboxPublisher.safeFailureCode(new IllegalStateException(secret));

        assertEquals("PUBLISH_ILLEGAL_STATE_EXCEPTION", code);
        assertFalse(code.contains(secret));
        assertFalse(code.contains("memory"));
    }

    @Test
    void missingOrAnonymousFailureTypesStillProduceBoundedMachineCodes() {
        assertEquals("PUBLISH_UNKNOWN", PersonalMemoryOutboxPublisher.safeFailureCode(null));
        String code = PersonalMemoryOutboxPublisher.safeFailureCode(new Exception("private text") { });
        assertEquals("PUBLISH_UNKNOWN", code);
        assertFalse(code.length() > 64);
    }

    @Test
    void publishedReceiptDropsOwnerAndPersonalMemoryPayload() {
        ContextMemoryOutboxEntity event = new ContextMemoryOutboxEntity();
        event.setId(42L);
        event.setEventType("PERSONAL_MEMORY_UPSERT");
        event.setPayloadJson("{\"tenantId\":\"tenant-secret\",\"content\":\"private memory body\"}");

        String receipt = PersonalMemoryOutboxPublisher.publishedReceipt(new ObjectMapper(), event);

        assertTrue(receipt.contains("reachai-personal-memory-index-receipt-v1"));
        assertTrue(receipt.contains("\"sourceVersion\":42"));
        assertFalse(receipt.contains("tenant-secret"));
        assertFalse(receipt.contains("private memory body"));
        assertFalse(receipt.contains("content"));
    }
}
