package com.enterprise.ai.control.context;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PersonalMemoryRecallServiceTest {

    @Test
    void buildsBoundedPayloadFromCanonicalQuery() {
        PersonalMemoryService memoryService = mock(PersonalMemoryService.class);
        when(memoryService.query(any(), any())).thenReturn(new PersonalMemoryService.QueryResult(
                List.of(new PersonalMemoryService.QueryHit(1L, "item-1", "PREFERENCE", "语言",
                        "默认使用中文", null, 4.25, "VERIFIED", LocalDateTime.now())),
                1, 6, 6000, false));
        PersonalMemoryRecallService recall = new PersonalMemoryRecallService(memoryService, 8, 6000);

        Map<String, Object> payload = recall.recall("AGENT", "default", "42",
                Map.of("agentId", "a1", "sessionId", "s1", "message", "用什么语言回答"));

        assertEquals("reachai-personal-memory-context-v1", payload.get("schema"));
        assertEquals(1, ((List<?>) payload.get("memories")).size());
        assertEquals("默认使用中文", ((Map<?, ?>) ((List<?>) payload.get("memories")).get(0)).get("content"));
    }

    @Test
    void respectsPerRequestOptOutAndDegradesOnRecallFailure() {
        PersonalMemoryService memoryService = mock(PersonalMemoryService.class);
        PersonalMemoryRecallService recall = new PersonalMemoryRecallService(memoryService, 8, 6000);

        assertTrue(recall.recall("AGENT", "default", "42",
                Map.of("message", "hello", "useMemory", false)).isEmpty());
        verify(memoryService, never()).query(any(), any());

        when(memoryService.query(any(), any())).thenThrow(new IllegalStateException("db unavailable"));
        assertTrue(recall.recall("AGENT", "default", "42", Map.of("message", "hello")).isEmpty());
    }

    @Test
    void ignoresUntrustedIdentitySourcesAndBlankUsers() {
        PersonalMemoryService memoryService = mock(PersonalMemoryService.class);
        PersonalMemoryRecallService recall = new PersonalMemoryRecallService(memoryService, 8, 6000);

        assertFalse(recall.recall("AGENT", "default", "42", Map.of()).containsKey("memories"));
        assertTrue(recall.recall("DEBUG", "default", "42", Map.of("message", "x")).isEmpty());
        assertTrue(recall.recall("AGENT", "default", "", Map.of("message", "x")).isEmpty());
    }
}
