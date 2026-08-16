package com.enterprise.ai.control.context;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.enterprise.ai.control.context.PersonalMemoryIdentityResolver.PersonalMemoryPrincipal;
import com.enterprise.ai.control.context.PersonalMemoryService.EraseAllCommand;
import com.enterprise.ai.control.context.PersonalMemoryService.ForgetCommand;
import com.enterprise.ai.control.context.PersonalMemoryService.QueryCommand;
import com.enterprise.ai.control.context.PersonalMemoryService.RememberCommand;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SuppressWarnings("unchecked")
class PersonalMemoryServiceTest {

    @BeforeAll
    static void initializeMyBatisMetadata() {
        Configuration configuration = new Configuration();
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(configuration, "pm-namespace"),
                ContextNamespaceEntity.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(configuration, "pm-item"),
                ContextItemEntity.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(configuration, "pm-binding"),
                ContextBindingEntity.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(configuration, "pm-evidence"),
                ContextEvidenceEntity.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(configuration, "pm-audit"),
                ContextAuditEventEntity.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(configuration, "pm-candidate"),
                ContextMemoryCandidateEntity.class);
    }

    @Test
    void explicitRememberCreatesPrivateVerifiedMemoryAndAudit() {
        Fixture fixture = fixture();
        when(fixture.namespaceMapper.selectOne(any(Wrapper.class)))
                .thenReturn((ContextNamespaceEntity) null, (ContextNamespaceEntity) null);
        org.mockito.Mockito.doAnswer(invocation -> {
            ContextNamespaceEntity entity = invocation.getArgument(0);
            entity.setId(3L);
            return 1;
        }).when(fixture.namespaceMapper).insert(any(ContextNamespaceEntity.class));
        when(fixture.itemMapper.selectOne(any(Wrapper.class))).thenReturn(null);
        org.mockito.Mockito.doAnswer(invocation -> {
            ContextItemEntity entity = invocation.getArgument(0);
            entity.setId(9L);
            return 1;
        }).when(fixture.itemMapper).insert(any(ContextItemEntity.class));

        PersonalMemoryService.RememberResult result = fixture.service.remember(principal("user-1"),
                new RememberCommand("PREFERENCE", "response-language", "回复语言", "请默认使用中文回答",
                        "默认中文", List.of("language"), null, "request-1",
                        "session-1", "trace-1", "agent-1"));

        assertTrue(result.created());
        assertFalse(result.idempotentReplay());
        assertEquals("VERIFIED", result.memory().trustLevel());
        assertEquals("ACTIVE", result.memory().status());
        ArgumentCaptor<ContextItemEntity> item = ArgumentCaptor.forClass(ContextItemEntity.class);
        verify(fixture.itemMapper).insert(item.capture());
        assertEquals("RUNTIME_USER", item.getValue().getMemoryLane());
        assertEquals("PRIVATE", item.getValue().getVisibility());
        assertEquals("USER_CONFIRMED", item.getValue().getSourceType());
        assertEquals(BigDecimal.ONE, item.getValue().getConfidence());
        verify(fixture.namespaceMapper).lockById(3L);
        verify(fixture.bindingMapper).insert(any(ContextBindingEntity.class));
        verify(fixture.evidenceMapper).insert(any(ContextEvidenceEntity.class));
        verify(fixture.auditMapper).insert(any(ContextAuditEventEntity.class));
    }

    @Test
    void duplicateContentReturnsExistingMemoryWithoutSecondInsert() {
        Fixture fixture = fixture();
        ContextNamespaceEntity namespace = namespace(3L, "user-1");
        ContextItemEntity existing = item(9L, 3L, "user-1", "FACT", "我住在青岛");
        when(fixture.namespaceMapper.selectOne(any(Wrapper.class))).thenReturn(namespace);
        when(fixture.itemMapper.selectOne(any(Wrapper.class))).thenReturn(existing);

        PersonalMemoryService.RememberResult result = fixture.service.remember(principal("user-1"),
                new RememberCommand("FACT", null, null, "我住在青岛", null, null,
                        null, null, null, null, null));

        assertTrue(result.idempotentReplay());
        assertFalse(result.created());
        assertEquals(9L, result.memory().id());
        verify(fixture.itemMapper, never()).insert(any());
    }

    @Test
    void newExplicitMemoryReturns429WhenOwnerActiveQuotaIsFull() {
        ContextNamespaceMapper namespaceMapper = mock(ContextNamespaceMapper.class);
        ContextItemMapper itemMapper = mock(ContextItemMapper.class);
        ContextBindingMapper bindingMapper = mock(ContextBindingMapper.class);
        ContextEvidenceMapper evidenceMapper = mock(ContextEvidenceMapper.class);
        ContextAuditEventMapper auditMapper = mock(ContextAuditEventMapper.class);
        PersonalMemoryService service = new PersonalMemoryService(
                namespaceMapper, itemMapper, bindingMapper, evidenceMapper, auditMapper,
                new ObjectMapper(), null, null, null, null, 1);
        when(namespaceMapper.selectOne(any(Wrapper.class))).thenReturn(namespace(3L, "user-1"));
        when(itemMapper.selectOne(any(Wrapper.class))).thenReturn(null);
        when(itemMapper.selectCount(any(Wrapper.class))).thenReturn(1L);

        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> service.remember(principal("user-1"), new RememberCommand(
                        "NOTE", null, null, "一条新的长期记忆", null, null,
                        null, null, "s1", "t1", "a1")));

        assertEquals(429, error.getStatusCode().value());
        verify(namespaceMapper).lockById(3L);
        verify(itemMapper, never()).insert(any());
        verify(bindingMapper, never()).insert(any());
    }

    @Test
    void rejectsCredentialLikeContentBeforeWritingAnything() {
        Fixture fixture = fixture();

        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> fixture.service.remember(principal("user-1"),
                        new RememberCommand("NOTE", null, null,
                                "api_key=sk-abcdefghijklmnopqrstuvwxyz123456", null, null,
                                null, null, null, null, null)));

        assertEquals(400, error.getStatusCode().value());
        verify(fixture.itemMapper, never()).insert(any());
        verify(fixture.namespaceMapper, never()).insert(any());
    }

    @Test
    void rejectsNullMutationAndQueryCommandsAsBadRequests() {
        Fixture fixture = fixture();

        assertEquals(400, assertThrows(ResponseStatusException.class,
                () -> fixture.service.remember(principal("user-1"), null)).getStatusCode().value());
        assertEquals(400, assertThrows(ResponseStatusException.class,
                () -> fixture.service.update(principal("user-1"), 9L, null)).getStatusCode().value());
        assertEquals(400, assertThrows(ResponseStatusException.class,
                () -> fixture.service.query(principal("user-1"), null)).getStatusCode().value());
        verify(fixture.itemMapper, never()).insert(any());
        verify(fixture.itemMapper, never()).updateById(any());
    }

    @Test
    void crossUserIdEnumerationReturnsNotFound() {
        Fixture fixture = fixture();
        ContextNamespaceEntity attackersNamespace = namespace(3L, "attacker");
        ContextItemEntity victimsItem = item(9L, 4L, "victim", "FACT", "private fact");
        when(fixture.namespaceMapper.selectOne(any(Wrapper.class))).thenReturn(attackersNamespace);
        when(fixture.itemMapper.selectById(9L)).thenReturn(victimsItem);

        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> fixture.service.get(principal("attacker"), 9L));

        assertEquals(404, error.getStatusCode().value());
        verify(fixture.auditMapper, never()).insert(any());
    }

    @Test
    void forgetErasesContentAndLeavesContentFreeAuditTombstone() {
        Fixture fixture = fixture();
        ContextNamespaceEntity namespace = namespace(3L, "user-1");
        ContextItemEntity existing = item(9L, 3L, "user-1", "FACT", "我的身份证信息不应继续保留");
        when(fixture.namespaceMapper.selectOne(any(Wrapper.class))).thenReturn(namespace);
        when(fixture.itemMapper.selectByIdForUpdate(9L)).thenReturn(existing);

        PersonalMemoryService.ForgetView result = fixture.service.forget(principal("user-1"), 9L,
                new ForgetCommand("用户主动删除", "session-1", "trace-1", "agent-1"));

        assertEquals("DELETED", result.status());
        verify(fixture.namespaceMapper).lockById(3L);
        verify(fixture.itemMapper).selectByIdForUpdate(9L);
        verify(fixture.itemMapper).update(isNull(), any(Wrapper.class));
        assertEquals("[deleted]", existing.getContent());
        assertEquals(null, existing.getTitle());
        assertEquals(null, existing.getSummary());
        assertNotNull(existing.getDeletedAt());
        ArgumentCaptor<ContextAuditEventEntity> audit = ArgumentCaptor.forClass(ContextAuditEventEntity.class);
        verify(fixture.auditMapper).insert(audit.capture());
        assertFalse(audit.getValue().getMetadataJson().contains("身份证"));
    }

    @Test
    void queryRanksRelevantItemsAndHonorsCharacterBudget() {
        Fixture fixture = fixture();
        ContextNamespaceEntity namespace = namespace(3L, "user-1");
        when(fixture.namespaceMapper.selectOne(any(Wrapper.class))).thenReturn(namespace);
        ContextItemEntity language = item(1L, 3L, "user-1", "PREFERENCE", "默认使用中文回答");
        language.setTitle("回复语言");
        ContextItemEntity city = item(2L, 3L, "user-1", "FACT", "我住在青岛");
        when(fixture.itemMapper.selectList(any(Wrapper.class))).thenReturn(List.of(city, language));

        PersonalMemoryService.QueryResult result = fixture.service.query(principal("user-1"),
                new QueryCommand("中文回答", null, 5, 256, "session-1", "trace-1", "agent-1"));

        assertEquals(1, result.hits().size());
        assertEquals(1L, result.hits().get(0).id());
        assertTrue(result.usedChars() <= result.maxChars());
        verify(fixture.auditMapper).insert(any(ContextAuditEventEntity.class));
    }

    @Test
    void queryKeepsGlobalPreferencesAndResolvesProfileIntentWithoutLexicalOverlap() {
        Fixture fixture = fixture();
        ContextNamespaceEntity namespace = namespace(3L, "user-1");
        when(fixture.namespaceMapper.selectOne(any(Wrapper.class))).thenReturn(namespace);
        ContextItemEntity language = item(1L, 3L, "user-1", "PREFERENCE", "请默认使用中文回答");
        language.setSourceRef(PersonalMemoryService.semanticSourceRef("response-language"));
        ContextItemEntity city = item(2L, 3L, "user-1", "FACT", "我住在青岛");
        city.setSourceRef(PersonalMemoryService.semanticSourceRef("profile:location"));
        ContextItemEntity unrelated = item(3L, 3L, "user-1", "NOTE", "我的纪念日是六月一日");
        when(fixture.itemMapper.selectList(any(Wrapper.class))).thenReturn(List.of(unrelated, language, city));

        PersonalMemoryService.QueryResult result = fixture.service.query(principal("user-1"),
                new QueryCommand("我住在哪个城市？", null, 5, 512,
                        "session-1", "trace-1", "agent-1"));

        assertEquals(List.of(2L, 1L),
                result.hits().stream().map(PersonalMemoryService.QueryHit::id).toList());
    }

    @Test
    void knowledgeProjectionCannotSuppressGlobalPreferences() {
        ContextNamespaceMapper namespaceMapper = mock(ContextNamespaceMapper.class);
        ContextItemMapper itemMapper = mock(ContextItemMapper.class);
        ContextBindingMapper bindingMapper = mock(ContextBindingMapper.class);
        ContextEvidenceMapper evidenceMapper = mock(ContextEvidenceMapper.class);
        ContextAuditEventMapper auditMapper = mock(ContextAuditEventMapper.class);
        PersonalMemoryKnowledgeQueryClient queryClient = mock(PersonalMemoryKnowledgeQueryClient.class);
        PersonalMemoryService service = new PersonalMemoryService(namespaceMapper, itemMapper, bindingMapper,
                evidenceMapper, auditMapper, new ObjectMapper(), null, queryClient, null);
        when(namespaceMapper.selectOne(any(Wrapper.class))).thenReturn(namespace(3L, "user-1"));
        when(queryClient.query("default", "user-1", "帮我准备发布说明", 15, 512))
                .thenReturn(new PersonalMemoryKnowledgeQueryClient.QueryAttempt(
                        PersonalMemoryKnowledgeQueryMode.ACTIVE, true, true,
                        List.of(99L), 1L, "SUCCESS"));
        ContextItemEntity projected = item(99L, 3L, "user-1", "NOTE", "发布说明需要列出验证证据");
        ContextItemEntity preference = item(1L, 3L, "user-1", "PREFERENCE", "请始终简洁回答");
        when(itemMapper.selectList(any(Wrapper.class)))
                .thenReturn(List.of(projected), List.of(preference));

        PersonalMemoryService.QueryResult result = service.query(principal("user-1"),
                new QueryCommand("帮我准备发布说明", null, 5, 512,
                        "session-1", "trace-1", "agent-1"));

        assertEquals(List.of(99L, 1L),
                result.hits().stream().map(PersonalMemoryService.QueryHit::id).toList());
    }

    @Test
    void shadowProjectionIsMeasuredButCannotChangeCanonicalAnswer() {
        ContextNamespaceMapper namespaceMapper = mock(ContextNamespaceMapper.class);
        ContextItemMapper itemMapper = mock(ContextItemMapper.class);
        ContextBindingMapper bindingMapper = mock(ContextBindingMapper.class);
        ContextEvidenceMapper evidenceMapper = mock(ContextEvidenceMapper.class);
        ContextAuditEventMapper auditMapper = mock(ContextAuditEventMapper.class);
        PersonalMemoryKnowledgeQueryClient queryClient = mock(PersonalMemoryKnowledgeQueryClient.class);
        PersonalMemoryRetrievalTelemetry telemetry = mock(PersonalMemoryRetrievalTelemetry.class);
        PersonalMemoryService service = new PersonalMemoryService(namespaceMapper, itemMapper, bindingMapper,
                evidenceMapper, auditMapper, new ObjectMapper(), null, queryClient, null, telemetry);
        when(namespaceMapper.selectOne(any(Wrapper.class))).thenReturn(namespace(3L, "user-1"));
        PersonalMemoryKnowledgeQueryClient.QueryAttempt attempt =
                new PersonalMemoryKnowledgeQueryClient.QueryAttempt(
                        PersonalMemoryKnowledgeQueryMode.SHADOW, true, true,
                        List.of(2L), 10L, "SUCCESS");
        when(queryClient.query("default", "user-1", "发布说明", 15, 512)).thenReturn(attempt);
        ContextItemEntity relevant = item(1L, 3L, "user-1", "NOTE", "发布说明要包含验证证据");
        ContextItemEntity projectedButIrrelevant = item(2L, 3L, "user-1", "NOTE", "完全无关的纪念日");
        when(itemMapper.selectList(any(Wrapper.class)))
                .thenReturn(List.of(relevant, projectedButIrrelevant), List.of());

        PersonalMemoryService.QueryResult result = service.query(principal("user-1"),
                new QueryCommand("发布说明", null, 5, 512,
                        "session-1", "trace-1", "agent-1"));

        assertEquals(List.of(1L), result.hits().stream().map(PersonalMemoryService.QueryHit::id).toList());
        verify(telemetry).record(attempt, List.of(1L), Set.of(1L, 2L), 5);
    }

    @Test
    void forgetWritesDeleteToTransactionalIndexOutbox() {
        ContextNamespaceMapper namespaceMapper = mock(ContextNamespaceMapper.class);
        ContextItemMapper itemMapper = mock(ContextItemMapper.class);
        ContextBindingMapper bindingMapper = mock(ContextBindingMapper.class);
        ContextEvidenceMapper evidenceMapper = mock(ContextEvidenceMapper.class);
        ContextAuditEventMapper auditMapper = mock(ContextAuditEventMapper.class);
        PersonalMemoryIndexOutboxService outbox = mock(PersonalMemoryIndexOutboxService.class);
        ContextMemoryCandidateMapper candidateMapper = mock(ContextMemoryCandidateMapper.class);
        PersonalMemoryService service = new PersonalMemoryService(namespaceMapper, itemMapper, bindingMapper,
                evidenceMapper, auditMapper, new ObjectMapper(), outbox, null, candidateMapper);
        ContextNamespaceEntity namespace = namespace(3L, "user-1");
        ContextItemEntity existing = item(9L, 3L, "user-1", "FACT", "待删除内容");
        when(namespaceMapper.selectOne(any(Wrapper.class))).thenReturn(namespace);
        when(itemMapper.selectByIdForUpdate(9L)).thenReturn(existing);

        service.forget(principal("user-1"), 9L, new ForgetCommand("delete", null, null, null));

        verify(outbox).enqueueDelete(any(), any());
        verify(candidateMapper).deleteRelatedToItem(9L);
        verify(bindingMapper).delete(any(Wrapper.class));
        verify(evidenceMapper).delete(any(Wrapper.class));
        verify(auditMapper).update(isNull(), any(Wrapper.class));
        assertEquals(null, existing.getCreatedBy());
        assertEquals(null, existing.getUpdatedBy());
        assertEquals(null, existing.getExpiresAt());
    }

    @Test
    void eraseAllRequiresExplicitMachineConfirmationBeforeAnyMutation() {
        Fixture fixture = fixture();

        ResponseStatusException failure = assertThrows(ResponseStatusException.class,
                () -> fixture.service.eraseAll(principal("user-1"),
                        new EraseAllCommand("wrong", "privacy", null, null, null)));

        assertEquals(400, failure.getStatusCode().value());
        verify(fixture.namespaceMapper, never()).selectOne(any(Wrapper.class));
        verify(fixture.itemMapper, never()).update(any(), any(Wrapper.class));
    }

    @Test
    void eraseAllTombstonesEveryMemoryDeletesCandidatesAndScrubsHistoricalAudit() {
        ContextNamespaceMapper namespaceMapper = mock(ContextNamespaceMapper.class);
        ContextItemMapper itemMapper = mock(ContextItemMapper.class);
        ContextBindingMapper bindingMapper = mock(ContextBindingMapper.class);
        ContextEvidenceMapper evidenceMapper = mock(ContextEvidenceMapper.class);
        ContextAuditEventMapper auditMapper = mock(ContextAuditEventMapper.class);
        PersonalMemoryIndexOutboxService outbox = mock(PersonalMemoryIndexOutboxService.class);
        ContextMemoryCandidateMapper candidateMapper = mock(ContextMemoryCandidateMapper.class);
        PersonalMemoryService service = new PersonalMemoryService(namespaceMapper, itemMapper, bindingMapper,
                evidenceMapper, auditMapper, new ObjectMapper(), outbox, null, candidateMapper);
        ContextNamespaceEntity namespace = namespace(3L, "user-1");
        ContextItemEntity first = item(9L, 3L, "user-1", "FACT", "first private memory");
        ContextItemEntity second = item(10L, 3L, "user-1", "PREFERENCE", "second private memory");
        first.setExpiresAt(LocalDateTime.now().plusDays(2));
        when(namespaceMapper.selectOne(any(Wrapper.class))).thenReturn(namespace);
        when(itemMapper.selectPersonalByNamespaceForUpdate(3L)).thenReturn(List.of(first, second));
        when(candidateMapper.deleteRelatedToItem(any())).thenReturn(1);
        when(candidateMapper.deleteAllPrivateForOwner("default", "user-1")).thenReturn(2);

        PersonalMemoryService.EraseAllView result = service.eraseAll(principal("user-1"),
                new EraseAllCommand(PersonalMemoryService.ERASE_ALL_CONFIRMATION,
                        "privacy request", "session-1", "trace-1", "agent-1"));

        assertEquals("CONTROL_PERSONAL_MEMORY_ERASED", result.status());
        assertEquals(2, result.memoriesErased());
        assertEquals(4, result.candidatesErased());
        assertFalse(result.idempotent());
        assertTrue(result.retainedDomains().contains("BUSINESS_SOURCE_SYSTEM"));
        assertEquals("DELETED", first.getStatus());
        assertEquals("[deleted]", first.getContent());
        assertEquals(null, first.getExpiresAt());
        assertEquals(null, first.getCreatedBy());
        assertEquals("DELETED", second.getStatus());
        verify(namespaceMapper).lockById(3L);
        verify(itemMapper, times(2)).update(isNull(), any(Wrapper.class));
        verify(bindingMapper, times(2)).delete(any(Wrapper.class));
        verify(evidenceMapper, times(2)).delete(any(Wrapper.class));
        verify(candidateMapper, times(2)).deleteRelatedToItem(any());
        verify(candidateMapper).deleteAllPrivateForOwner("default", "user-1");
        verify(outbox, times(2)).enqueueDelete(any(), any());
        verify(auditMapper, times(3)).update(isNull(), any(Wrapper.class));
        ArgumentCaptor<ContextAuditEventEntity> audit = ArgumentCaptor.forClass(ContextAuditEventEntity.class);
        verify(auditMapper).insert(audit.capture());
        assertEquals("ERASE_ALL", audit.getValue().getEventType());
        assertEquals("SELF_ERASE_ALL", audit.getValue().getReason());
        assertFalse(audit.getValue().getMetadataJson().contains("first private memory"));
        assertFalse(audit.getValue().getMetadataJson().contains("second private memory"));
    }

    @Test
    void orchestratedEraseCorrelatesEveryDeleteWithoutChangingSelfServiceContract() {
        ContextNamespaceMapper namespaceMapper = mock(ContextNamespaceMapper.class);
        ContextItemMapper itemMapper = mock(ContextItemMapper.class);
        ContextBindingMapper bindingMapper = mock(ContextBindingMapper.class);
        ContextEvidenceMapper evidenceMapper = mock(ContextEvidenceMapper.class);
        ContextAuditEventMapper auditMapper = mock(ContextAuditEventMapper.class);
        PersonalMemoryIndexOutboxService outbox = mock(PersonalMemoryIndexOutboxService.class);
        ContextMemoryCandidateMapper candidateMapper = mock(ContextMemoryCandidateMapper.class);
        PersonalMemoryService service = new PersonalMemoryService(namespaceMapper, itemMapper, bindingMapper,
                evidenceMapper, auditMapper, new ObjectMapper(), outbox, null, candidateMapper);
        when(namespaceMapper.selectOne(any(Wrapper.class))).thenReturn(namespace(3L, "user-1"));
        when(itemMapper.selectPersonalByNamespaceForUpdate(3L)).thenReturn(List.of(
                item(9L, 3L, "user-1", "FACT", "private"),
                item(10L, 3L, "user-1", "RULE", "private too")));

        service.eraseAllForOrchestration(principal("user-1"),
                new EraseAllCommand(PersonalMemoryService.ERASE_ALL_CONFIRMATION,
                        "privacy", null, null, null), "erase-request-42");

        verify(outbox, times(2)).enqueueDelete(any(), any(),
                org.mockito.ArgumentMatchers.eq("erase-request-42"));
        verify(outbox, never()).enqueueDelete(any(), any());
    }

    @Test
    void lifecyclePhysicallyScrubsExpiredMemoryAndPublishesTombstone() {
        ContextNamespaceMapper namespaceMapper = mock(ContextNamespaceMapper.class);
        ContextItemMapper itemMapper = mock(ContextItemMapper.class);
        ContextBindingMapper bindingMapper = mock(ContextBindingMapper.class);
        ContextEvidenceMapper evidenceMapper = mock(ContextEvidenceMapper.class);
        ContextAuditEventMapper auditMapper = mock(ContextAuditEventMapper.class);
        PersonalMemoryIndexOutboxService outbox = mock(PersonalMemoryIndexOutboxService.class);
        ContextMemoryCandidateMapper candidateMapper = mock(ContextMemoryCandidateMapper.class);
        PersonalMemoryService service = new PersonalMemoryService(namespaceMapper, itemMapper, bindingMapper,
                evidenceMapper, auditMapper, new ObjectMapper(), outbox, null, candidateMapper);
        ContextNamespaceEntity namespace = namespace(3L, "user-1");
        ContextItemEntity expired = item(9L, 3L, "user-1", "FACT", "expired private content");
        expired.setTitle("private title");
        expired.setSummary("private summary");
        expired.setSourceRef("private-source");
        expired.setExpiresAt(LocalDateTime.now().minusMinutes(1));
        when(itemMapper.selectList(any(Wrapper.class))).thenReturn(List.of(expired));
        when(namespaceMapper.selectById(3L)).thenReturn(namespace);
        when(itemMapper.selectByIdForUpdate(9L)).thenReturn(expired);

        int count = service.expireDue(50);

        assertEquals(1, count);
        assertEquals("DELETED", expired.getStatus());
        assertEquals("[deleted]", expired.getContent());
        assertEquals(null, expired.getTitle());
        assertEquals(null, expired.getSummary());
        assertEquals(null, expired.getSourceRef());
        verify(namespaceMapper).lockById(3L);
        verify(itemMapper).update(isNull(), any(Wrapper.class));
        verify(candidateMapper).deleteRelatedToItem(9L);
        verify(bindingMapper).delete(any(Wrapper.class));
        verify(evidenceMapper).delete(any(Wrapper.class));
        verify(auditMapper).update(isNull(), any(Wrapper.class));
        verify(outbox).enqueueDelete(any(), any());
        ArgumentCaptor<ContextAuditEventEntity> audit = ArgumentCaptor.forClass(ContextAuditEventEntity.class);
        verify(auditMapper).insert(audit.capture());
        assertEquals("EXPIRE", audit.getValue().getEventType());
        assertEquals("SYSTEM", audit.getValue().getActorType());
        assertEquals("RETENTION_EXPIRED", audit.getValue().getReason());
        assertFalse(audit.getValue().getMetadataJson().contains("expired private content"));
        assertFalse(audit.getValue().getMetadataJson().contains("deletedContentSha256"));
    }

    private static Fixture fixture() {
        ContextNamespaceMapper namespaceMapper = mock(ContextNamespaceMapper.class);
        ContextItemMapper itemMapper = mock(ContextItemMapper.class);
        ContextBindingMapper bindingMapper = mock(ContextBindingMapper.class);
        ContextEvidenceMapper evidenceMapper = mock(ContextEvidenceMapper.class);
        ContextAuditEventMapper auditMapper = mock(ContextAuditEventMapper.class);
        return new Fixture(new PersonalMemoryService(namespaceMapper, itemMapper, bindingMapper,
                evidenceMapper, auditMapper, new ObjectMapper()), namespaceMapper, itemMapper,
                bindingMapper, evidenceMapper, auditMapper);
    }

    private static PersonalMemoryPrincipal principal(String userId) {
        return new PersonalMemoryPrincipal("default", userId, 1L, "platform-user", "platform-session");
    }

    private static ContextNamespaceEntity namespace(Long id, String userId) {
        ContextNamespaceEntity namespace = new ContextNamespaceEntity();
        namespace.setId(id);
        namespace.setNamespaceType("USER");
        namespace.setTenantId("default");
        namespace.setOwnerType("RUNTIME_USER");
        namespace.setOwnerId(userId);
        namespace.setStatus("ACTIVE");
        return namespace;
    }

    private static ContextItemEntity item(Long id, Long namespaceId, String userId, String type, String content) {
        ContextItemEntity item = new ContextItemEntity();
        item.setId(id);
        item.setItemKey("ctx-item-" + id);
        item.setNamespaceId(namespaceId);
        item.setItemType(type);
        item.setMemoryLane("RUNTIME_USER");
        item.setContent(content);
        item.setTrustLevel("VERIFIED");
        item.setVisibility("PRIVATE");
        item.setStatus("ACTIVE");
        item.setCreatedBy(userId);
        item.setUpdatedBy(userId);
        item.setCreatedAt(LocalDateTime.now());
        item.setUpdatedAt(LocalDateTime.now());
        return item;
    }

    private record Fixture(PersonalMemoryService service,
                           ContextNamespaceMapper namespaceMapper,
                           ContextItemMapper itemMapper,
                           ContextBindingMapper bindingMapper,
                           ContextEvidenceMapper evidenceMapper,
                           ContextAuditEventMapper auditMapper) {
    }
}
