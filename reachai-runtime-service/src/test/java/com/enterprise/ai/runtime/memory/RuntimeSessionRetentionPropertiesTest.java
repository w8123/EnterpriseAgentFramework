package com.enterprise.ai.runtime.memory;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;

class RuntimeSessionRetentionPropertiesTest {

    @Test
    void rejectsUnsafeOrUnboundedConfiguration() {
        assertThrows(IllegalArgumentException.class,
                () -> new RuntimeSessionRetentionProperties(true, 0, 24, 100, 300));
        assertThrows(IllegalArgumentException.class,
                () -> new RuntimeSessionRetentionProperties(true, 30, 0, 100, 300));
        assertThrows(IllegalArgumentException.class,
                () -> new RuntimeSessionRetentionProperties(true, 30, 24, 501, 300));
        assertThrows(IllegalArgumentException.class,
                () -> new RuntimeSessionRetentionProperties(true, 30, 24, 100, 29));

        new RuntimeSessionRetentionProperties(true, 30, 24, 100, 300);
    }

    @Test
    void auditReferenceCannotCarryFreeFormConversationText() {
        assertThrows(IllegalArgumentException.class,
                () -> RuntimeSessionRetentionSupport.referenceId("customer said erase everything"));
        RuntimeSessionRetentionSupport.referenceId("CASE-2026/0007");
    }
}
