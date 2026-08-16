package com.enterprise.ai.control.context;

import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.enterprise.ai.control.context.PersonalMemoryIdentityResolver.PersonalMemoryPrincipal;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class PersonalMemoryIndexOutboxServiceTest {

    @BeforeAll
    static void metadata() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new Configuration(), "pm-outbox"),
                ContextMemoryOutboxEntity.class);
    }

    @Test
    void replacesPlaceholderWithDatabaseGeneratedMonotonicVersion() throws Exception {
        ContextMemoryOutboxMapper mapper = mock(ContextMemoryOutboxMapper.class);
        doAnswer(invocation -> {
            ContextMemoryOutboxEntity row = invocation.getArgument(0);
            row.setId(42L);
            return 1;
        }).when(mapper).insert(any(ContextMemoryOutboxEntity.class));
        PersonalMemoryIndexOutboxService service = new PersonalMemoryIndexOutboxService(mapper, new ObjectMapper());
        ContextItemEntity item = item("长期偏好内容");

        service.enqueueUpsert(principal(), item);

        ArgumentCaptor<ContextMemoryOutboxEntity> updated =
                ArgumentCaptor.forClass(ContextMemoryOutboxEntity.class);
        verify(mapper).updateById(updated.capture());
        JsonNode payload = new ObjectMapper().readTree(updated.getValue().getPayloadJson());
        assertEquals(42L, payload.path("sourceVersion").asLong());
        assertEquals("user-1", payload.path("runtimeUserId").asText());
        assertEquals("长期偏好内容", payload.path("content").asText());
    }

    @Test
    void deleteEventContainsOnlyProjectionRoutingAndNeverForgottenContentOrHash() throws Exception {
        ContextMemoryOutboxMapper mapper = mock(ContextMemoryOutboxMapper.class);
        doAnswer(invocation -> {
            ContextMemoryOutboxEntity row = invocation.getArgument(0);
            row.setId(43L);
            return 1;
        }).when(mapper).insert(any(ContextMemoryOutboxEntity.class));
        PersonalMemoryIndexOutboxService service = new PersonalMemoryIndexOutboxService(mapper, new ObjectMapper());

        service.enqueueDelete(principal(), item("[deleted]"));

        ArgumentCaptor<ContextMemoryOutboxEntity> updated =
                ArgumentCaptor.forClass(ContextMemoryOutboxEntity.class);
        ArgumentCaptor<String> historicalReceipt = ArgumentCaptor.forClass(String.class);
        verify(mapper).updateById(updated.capture());
        verify(mapper).scrubAndSupersedePersonalMemory(
                eq("9"), historicalReceipt.capture(), any(java.time.LocalDateTime.class));
        JsonNode payload = new ObjectMapper().readTree(updated.getValue().getPayloadJson());
        assertEquals(9L, payload.path("memoryId").asLong());
        assertEquals("default", payload.path("tenantId").asText());
        assertEquals("user-1", payload.path("runtimeUserId").asText());
        assertEquals(43L, payload.path("sourceVersion").asLong());
        assertFalse(payload.has("deletedContentSha256"));
        assertFalse(payload.has("content"));
        assertFalse(updated.getValue().getPayloadJson().contains("原始私密内容"));

        JsonNode receipt = new ObjectMapper().readTree(historicalReceipt.getValue());
        assertEquals("reachai-personal-memory-index-erasure-receipt-v1", receipt.path("schema").asText());
        assertTrue(receipt.path("deleted").asBoolean());
        assertFalse(historicalReceipt.getValue().contains("default"));
        assertFalse(historicalReceipt.getValue().contains("user-1"));
        assertFalse(historicalReceipt.getValue().contains("9"));
        assertFalse(historicalReceipt.getValue().contains("abc123"));
        assertFalse(historicalReceipt.getValue().contains("原始私密内容"));
    }

    @Test
    void historicalErasureReceiptContainsNoOwnerMemoryOrContentIdentifiers() throws Exception {
        String receiptJson = PersonalMemoryIndexOutboxService.erasureReceipt(new ObjectMapper());
        JsonNode receipt = new ObjectMapper().readTree(receiptJson);

        assertEquals("reachai-personal-memory-index-erasure-receipt-v1", receipt.path("schema").asText());
        assertTrue(receipt.path("deleted").asBoolean());
        assertFalse(receipt.has("tenantId"));
        assertFalse(receipt.has("runtimeUserId"));
        assertFalse(receipt.has("memoryId"));
        assertFalse(receipt.has("deletedContentSha256"));
        assertFalse(receipt.has("content"));
        assertFalse(receiptJson.contains("default"));
        assertFalse(receiptJson.contains("user-1"));
        assertFalse(receiptJson.contains("abc123"));
        assertFalse(receiptJson.contains("原始私密内容"));
    }

    @Test
    void correlatedDeletePersistsOnlyBoundedMachineCorrelationId() {
        ContextMemoryOutboxMapper mapper = mock(ContextMemoryOutboxMapper.class);
        doAnswer(invocation -> {
            ContextMemoryOutboxEntity row = invocation.getArgument(0);
            row.setId(44L);
            return 1;
        }).when(mapper).insert(any(ContextMemoryOutboxEntity.class));
        PersonalMemoryIndexOutboxService service =
                new PersonalMemoryIndexOutboxService(mapper, new ObjectMapper());

        long outboxId = service.enqueueDelete(principal(), item("[deleted]"), "erase-request-42");

        ArgumentCaptor<ContextMemoryOutboxEntity> inserted =
                ArgumentCaptor.forClass(ContextMemoryOutboxEntity.class);
        verify(mapper).insert(inserted.capture());
        assertEquals(44L, outboxId);
        assertEquals("erase-request-42", inserted.getValue().getCorrelationId());
        assertFalse(inserted.getValue().getPayloadJson().contains("erase-request-42"));
    }

    @Test
    void invalidCorrelationIsRejectedBeforeOutboxInsert() {
        ContextMemoryOutboxMapper mapper = mock(ContextMemoryOutboxMapper.class);
        PersonalMemoryIndexOutboxService service =
                new PersonalMemoryIndexOutboxService(mapper, new ObjectMapper());

        assertThrows(IllegalArgumentException.class,
                () -> service.enqueueDelete(principal(), item("[deleted]"), "bad owner value"));

        verify(mapper, never()).insert(any(ContextMemoryOutboxEntity.class));
    }

    private static ContextItemEntity item(String content) {
        ContextItemEntity item = new ContextItemEntity();
        item.setId(9L);
        item.setItemType("PREFERENCE");
        item.setContent(content);
        item.setTrustLevel("VERIFIED");
        return item;
    }

    private static PersonalMemoryPrincipal principal() {
        return new PersonalMemoryPrincipal("default", "user-1", 1L, "alice", "session-1");
    }
}
