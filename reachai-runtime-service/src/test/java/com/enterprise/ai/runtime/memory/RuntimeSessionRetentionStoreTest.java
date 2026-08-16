package com.enterprise.ai.runtime.memory;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RuntimeSessionRetentionStoreTest {

    @BeforeAll
    static void initializeMybatisMetadata() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(
                new Configuration(), "runtime-retention-store-test");
        TableInfoHelper.initTableInfo(assistant, RuntimeConversationSessionEntity.class);
        TableInfoHelper.initTableInfo(assistant, RuntimeConversationEventEntity.class);
        TableInfoHelper.initTableInfo(assistant, RuntimeSessionRetentionPolicyEntity.class);
    }

    @Test
    void legalHoldIsAtomicallySetAndAuditedWithoutConversationContent() {
        RuntimeConversationSessionMapper sessionMapper = mock(RuntimeConversationSessionMapper.class);
        RuntimeSessionRetentionAuditMapper auditMapper = mock(RuntimeSessionRetentionAuditMapper.class);
        RuntimeConversationSessionEntity session = session("ACTIVE", false);
        when(sessionMapper.selectOne(any(Wrapper.class))).thenReturn(session);
        when(sessionMapper.update(any(), any(Wrapper.class))).thenReturn(1);
        RuntimeSessionRetentionStore store = store(sessionMapper, auditMapper);

        RuntimeConversationSessionEntity updated = store.setLegalHold(
                "tenant-a", "session-a", true, "LEGAL_CASE", "CASE-17",
                "a".repeat(64), LocalDateTime.of(2026, 8, 15, 12, 0));

        assertTrue(updated.getLegalHold());
        assertEquals("LEGAL_CASE", updated.getLegalHoldReasonCode());
        ArgumentCaptor<RuntimeSessionRetentionAuditEntity> audit =
                ArgumentCaptor.forClass(RuntimeSessionRetentionAuditEntity.class);
        verify(auditMapper).insert(audit.capture());
        assertEquals("LEGAL_HOLD_SET", audit.getValue().getEventType());
        assertEquals(64, audit.getValue().getSessionIdHash().length());
        assertEquals("CASE-17", audit.getValue().getReferenceId());
    }

    @Test
    void manualEraseCannotBypassLegalHold() {
        RuntimeConversationSessionMapper sessionMapper = mock(RuntimeConversationSessionMapper.class);
        RuntimeConversationSessionEntity held = session("ACTIVE", true);
        when(sessionMapper.selectOne(any(Wrapper.class))).thenReturn(held);
        RuntimeSessionRetentionStore store = store(
                sessionMapper, mock(RuntimeSessionRetentionAuditMapper.class));

        RuntimeSessionRetentionException failure = assertThrows(
                RuntimeSessionRetentionException.class,
                () -> store.claimManualPurge(
                        "tenant-a", "session-a", "ERASE_REQUEST", null,
                        "a".repeat(64), "owner", LocalDateTime.now()));

        assertEquals(HttpStatus.LOCKED, failure.status());
    }

    @Test
    void finalPurgeDeletesEventsThenClaimedSessionAndLeavesMetadataAudit() {
        RuntimeConversationSessionMapper sessionMapper = mock(RuntimeConversationSessionMapper.class);
        RuntimeConversationEventMapper eventMapper = mock(RuntimeConversationEventMapper.class);
        RuntimeSessionRetentionAuditMapper auditMapper = mock(RuntimeSessionRetentionAuditMapper.class);
        when(sessionMapper.delete(any(Wrapper.class))).thenReturn(1);
        RuntimeSessionRetentionStore store = new RuntimeSessionRetentionStore(
                sessionMapper,
                eventMapper,
                mock(RuntimeSessionRetentionPolicyMapper.class),
                auditMapper,
                properties());
        RuntimeConversationSessionEntity session = session("PURGING", false);
        session.setLifecycleOwner("owner");
        session.setLifecycleReasonCode("RETENTION_ACTIVE");
        session.setLifecyclePreviousStatus("ACTIVE");

        store.finalizePurge(session, "owner", "SYSTEM", null, null, LocalDateTime.now());

        verify(eventMapper).delete(any(Wrapper.class));
        verify(sessionMapper).delete(any(Wrapper.class));
        ArgumentCaptor<RuntimeSessionRetentionAuditEntity> audit =
                ArgumentCaptor.forClass(RuntimeSessionRetentionAuditEntity.class);
        verify(auditMapper).insert(audit.capture());
        assertEquals("PURGE_COMPLETED", audit.getValue().getEventType());
        assertEquals("ERASED", audit.getValue().getResultStatus());
    }

    @Test
    void retentionClaimMovesOnlyAnUnheldIdleRowIntoPurging() {
        RuntimeConversationSessionMapper sessionMapper = mock(RuntimeConversationSessionMapper.class);
        RuntimeSessionRetentionAuditMapper auditMapper = mock(RuntimeSessionRetentionAuditMapper.class);
        when(sessionMapper.update(any(), any(Wrapper.class))).thenReturn(1);
        RuntimeSessionRetentionStore store = store(sessionMapper, auditMapper);
        RuntimeConversationSessionEntity candidate = session("ACTIVE", false);
        candidate.setUpdatedAt(LocalDateTime.of(2026, 7, 1, 0, 0));

        assertTrue(store.claimRetentionPurge(
                candidate, "owner", LocalDateTime.of(2026, 8, 15, 0, 0)));
        assertEquals("PURGING", candidate.getStatus());
        assertEquals("RETENTION_ACTIVE", candidate.getLifecycleReasonCode());
        verify(sessionMapper).update(any(), any(Wrapper.class));
    }

    private static RuntimeSessionRetentionStore store(
            RuntimeConversationSessionMapper sessionMapper,
            RuntimeSessionRetentionAuditMapper auditMapper) {
        return new RuntimeSessionRetentionStore(
                sessionMapper,
                mock(RuntimeConversationEventMapper.class),
                mock(RuntimeSessionRetentionPolicyMapper.class),
                auditMapper,
                properties());
    }

    private static RuntimeSessionRetentionProperties properties() {
        return new RuntimeSessionRetentionProperties(true, 30, 24, 100, 300);
    }

    private static RuntimeConversationSessionEntity session(String status, boolean legalHold) {
        RuntimeConversationSessionEntity session = new RuntimeConversationSessionEntity();
        session.setId(7L);
        session.setTenantId("tenant-a");
        session.setSessionId("session-a");
        session.setStateUserKey("user-key");
        session.setStateSessionKey("session-key");
        session.setAgentId("agent-a");
        session.setStatus(status);
        session.setLegalHold(legalHold);
        session.setEventCount(2);
        return session;
    }
}
