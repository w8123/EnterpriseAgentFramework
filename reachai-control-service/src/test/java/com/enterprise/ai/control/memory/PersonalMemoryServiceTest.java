package com.enterprise.ai.control.memory;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.enterprise.ai.control.memory.PersonalMemoryIdentityResolver.PersonalMemoryPrincipal;
import com.enterprise.ai.control.memory.PersonalMemoryService.EraseAllCommand;
import com.enterprise.ai.control.memory.PersonalMemoryService.QueryCommand;
import com.enterprise.ai.control.memory.PersonalMemoryService.RememberCommand;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
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
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(configuration, "pm"), PersonalMemoryEntity.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(configuration, "pm-audit"),
                PersonalMemoryAuditEntity.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(configuration, "pm-candidate"),
                PersonalMemoryCandidateEntity.class);
    }

    @Test
    void rememberStoresOneOwnedRowWithItsSourceAgentAndPublishesIt() {
        Fixture fixture = fixture();
        doAnswer(invocation -> {
            invocation.<PersonalMemoryEntity>getArgument(0).setId(9L);
            return 1;
        }).when(fixture.memoryMapper).insert(any(PersonalMemoryEntity.class));

        PersonalMemoryService.RememberResult result = fixture.service.remember(principal("user-1"),
                new RememberCommand("PREFERENCE", "response-language", "回复语言", "请默认使用中文回答",
                        List.of("language"), null, "request-1", PersonalMemoryService.SOURCE_CONVERSATION,
                        "session-1", "trace-1", "agent-1"));

        assertTrue(result.created());
        assertEquals(List.of("language"), result.memory().tags());
        ArgumentCaptor<PersonalMemoryEntity> row = ArgumentCaptor.forClass(PersonalMemoryEntity.class);
        verify(fixture.memoryMapper).insert(row.capture());
        assertEquals("default", row.getValue().getTenantId());
        assertEquals("user-1", row.getValue().getOwnerId());
        assertEquals("agent-1", row.getValue().getSourceAgentId());
        assertEquals("CONVERSATION", row.getValue().getSourceType());
        assertEquals(PersonalMemoryService.semanticSourceRef("response-language"), row.getValue().getSemanticKey());
        assertEquals(PersonalMemoryService.contentHash("请默认使用中文回答"), row.getValue().getContentSha256());
        verify(fixture.outbox).enqueueUpsert(any(), eq(row.getValue()));
        ArgumentCaptor<PersonalMemoryAuditEntity> audit = ArgumentCaptor.forClass(PersonalMemoryAuditEntity.class);
        verify(fixture.auditMapper).insert(audit.capture());
        assertFalse(audit.getValue().getMetadataJson().contains("中文"));
    }

    @Test
    void identicalContentReturnsTheExistingMemory() {
        Fixture fixture = fixture();
        PersonalMemoryEntity existing = memory(9L, "user-1", "PREFERENCE", "请始终简洁回答");
        when(fixture.memoryMapper.selectOne(any(Wrapper.class))).thenReturn(existing);

        PersonalMemoryService.RememberResult result = fixture.service.remember(principal("user-1"),
                new RememberCommand("PREFERENCE", null, null, "请始终  简洁回答", null, null, null,
                        null, null, null, null));

        assertTrue(result.idempotentReplay());
        assertEquals(9L, result.memory().id());
        verify(fixture.memoryMapper, never()).insert(any(PersonalMemoryEntity.class));
    }

    @Test
    void sameSemanticKeyReplacesTheEarlierFact() {
        Fixture fixture = fixture();
        PersonalMemoryEntity city = memory(9L, "user-1", "FACT", "我住在青岛");
        city.setSemanticKey(PersonalMemoryService.semanticSourceRef("profile:location"));
        when(fixture.memoryMapper.selectOne(any(Wrapper.class))).thenReturn(city);

        PersonalMemoryService.RememberResult result = fixture.service.remember(principal("user-1"),
                new RememberCommand("FACT", "profile:location", "常住地", "我住在上海", null, null, null,
                        null, null, null, "agent-2"));

        assertTrue(result.updated());
        assertEquals("我住在上海", city.getContent());
        assertEquals("agent-2", city.getSourceAgentId());
        verify(fixture.memoryMapper).updateById(city);
        verify(fixture.memoryMapper, never()).insert(any(PersonalMemoryEntity.class));
    }

    @Test
    void newMemoryReturns429WhenTheOwnerQuotaIsFull() {
        PersonalMemoryMapper memoryMapper = mock(PersonalMemoryMapper.class);
        PersonalMemoryService service = new PersonalMemoryService(memoryMapper, mock(PersonalMemoryCandidateMapper.class),
                mock(PersonalMemoryAuditMapper.class), new ObjectMapper(), null, null, null, 2);
        when(memoryMapper.selectCount(any(Wrapper.class))).thenReturn(2L);

        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> service.remember(principal("user-1"), new RememberCommand("NOTE", null, null, "新的记忆",
                        null, null, null, null, null, null, null)));

        assertEquals(429, error.getStatusCode().value());
        verify(memoryMapper, never()).insert(any(PersonalMemoryEntity.class));
    }

    @Test
    void rejectsCredentialLikeContentAndNullCommandsBeforeWriting() {
        Fixture fixture = fixture();

        assertEquals(400, assertThrows(ResponseStatusException.class,
                () -> fixture.service.remember(principal("user-1"), new RememberCommand("NOTE", null, null,
                        "api_key=sk-abcdefghijklmnopqrstuvwxyz123456", null, null, null, null, null, null, null)))
                .getStatusCode().value());
        assertEquals(400, assertThrows(ResponseStatusException.class,
                () -> fixture.service.remember(principal("user-1"), null)).getStatusCode().value());
        assertEquals(400, assertThrows(ResponseStatusException.class,
                () -> fixture.service.update(principal("user-1"), 9L, null)).getStatusCode().value());
        assertEquals(400, assertThrows(ResponseStatusException.class,
                () -> fixture.service.query(principal("user-1"), null)).getStatusCode().value());
        verify(fixture.memoryMapper, never()).insert(any(PersonalMemoryEntity.class));
    }

    @Test
    void anotherUsersMemoryIsNotFound() {
        Fixture fixture = fixture();
        when(fixture.memoryMapper.selectById(9L)).thenReturn(memory(9L, "victim", "FACT", "private fact"));
        when(fixture.memoryMapper.selectByIdForUpdate(9L)).thenReturn(memory(9L, "victim", "FACT", "private fact"));

        assertEquals(404, assertThrows(ResponseStatusException.class,
                () -> fixture.service.get(principal("attacker"), 9L)).getStatusCode().value());
        assertEquals(404, assertThrows(ResponseStatusException.class,
                () -> fixture.service.forget(principal("attacker"), 9L)).getStatusCode().value());
        verify(fixture.memoryMapper, never()).deleteById(any(Long.class));
    }

    @Test
    void forgetDeletesTheRowItsCandidatesAndProjection() {
        Fixture fixture = fixture();
        when(fixture.memoryMapper.selectByIdForUpdate(9L)).thenReturn(memory(9L, "user-1", "FACT", "我的身份证信息"));

        fixture.service.forget(principal("user-1"), 9L);

        verify(fixture.memoryMapper).deleteById(9L);
        verify(fixture.candidateMapper).deleteRelatedToMemory(9L);
        verify(fixture.auditMapper).update(isNull(), any(Wrapper.class));
        verify(fixture.outbox).enqueueDelete(any(), eq(9L), isNull());
        ArgumentCaptor<PersonalMemoryAuditEntity> audit = ArgumentCaptor.forClass(PersonalMemoryAuditEntity.class);
        verify(fixture.auditMapper).insert(audit.capture());
        assertEquals("DELETE", audit.getValue().getEventType());
        assertEquals(null, audit.getValue().getMemoryId());
    }

    @Test
    void eraseAllNeedsTheConfirmationAndCorrelatesEveryDelete() {
        Fixture fixture = fixture();
        assertEquals(400, assertThrows(ResponseStatusException.class,
                () -> fixture.service.eraseAll(principal("user-1"), new EraseAllCommand("yes")))
                .getStatusCode().value());
        verify(fixture.memoryMapper, never()).selectOwnedForUpdate(any(), any());

        when(fixture.memoryMapper.selectOwnedForUpdate("default", "user-1")).thenReturn(List.of(
                memory(1L, "user-1", "FACT", "a"), memory(2L, "user-1", "NOTE", "b")));
        when(fixture.candidateMapper.deleteAllForOwner("default", "user-1")).thenReturn(3);

        PersonalMemoryService.EraseAllView result = fixture.service.eraseAll(principal("user-1"),
                new EraseAllCommand(PersonalMemoryService.ERASE_ALL_CONFIRMATION), "request-42");

        assertEquals(2, result.memoriesErased());
        assertEquals(3, result.candidatesErased());
        verify(fixture.memoryMapper).deleteById(1L);
        verify(fixture.memoryMapper).deleteById(2L);
        verify(fixture.outbox).enqueueDelete(any(), eq(1L), eq("request-42"));
        verify(fixture.outbox).enqueueDelete(any(), eq(2L), eq("request-42"));
        verify(fixture.auditMapper).delete(any(Wrapper.class));
    }

    @Test
    void queryRanksRelevantMemoriesWithinTheCharacterBudget() {
        Fixture fixture = fixture();
        PersonalMemoryEntity language = memory(1L, "user-1", "PREFERENCE", "默认使用中文回答");
        language.setTitle("回复语言");
        PersonalMemoryEntity city = memory(2L, "user-1", "FACT", "我住在青岛");
        when(fixture.memoryMapper.selectList(any(Wrapper.class))).thenReturn(List.of(city, language));

        PersonalMemoryService.QueryResult result = fixture.service.query(principal("user-1"),
                new QueryCommand("中文回答", null, 5, 256, "session-1", "trace-1", "agent-1"));

        assertEquals(List.of(1L), result.hits().stream().map(PersonalMemoryService.QueryHit::id).toList());
        assertTrue(result.usedChars() <= result.maxChars());
    }

    @Test
    void queryKeepsPreferencesAndAnswersProfileIntentWithoutLexicalOverlap() {
        Fixture fixture = fixture();
        PersonalMemoryEntity language = memory(1L, "user-1", "PREFERENCE", "请默认使用中文回答");
        language.setSemanticKey(PersonalMemoryService.semanticSourceRef("response-language"));
        PersonalMemoryEntity city = memory(2L, "user-1", "FACT", "我住在青岛");
        city.setSemanticKey(PersonalMemoryService.semanticSourceRef("profile:location"));
        PersonalMemoryEntity unrelated = memory(3L, "user-1", "NOTE", "我的纪念日是六月一日");
        when(fixture.memoryMapper.selectList(any(Wrapper.class))).thenReturn(List.of(unrelated, language, city));

        PersonalMemoryService.QueryResult result = fixture.service.query(principal("user-1"),
                new QueryCommand("我住在哪个城市？", null, 5, 512, null, null, null));

        assertEquals(List.of(2L, 1L), result.hits().stream().map(PersonalMemoryService.QueryHit::id).toList());
    }

    @Test
    void knowledgeProjectionCannotSuppressPreferencesAndShadowModeCannotRerank() {
        PersonalMemoryMapper memoryMapper = mock(PersonalMemoryMapper.class);
        PersonalMemoryKnowledgeQueryClient queryClient = mock(PersonalMemoryKnowledgeQueryClient.class);
        PersonalMemoryRetrievalTelemetry telemetry = mock(PersonalMemoryRetrievalTelemetry.class);
        PersonalMemoryService service = new PersonalMemoryService(memoryMapper, mock(PersonalMemoryCandidateMapper.class),
                mock(PersonalMemoryAuditMapper.class), new ObjectMapper(), null, queryClient, telemetry, 1000);
        when(queryClient.query("default", "user-1", "帮我准备发布说明", 15, 512))
                .thenReturn(new PersonalMemoryKnowledgeQueryClient.QueryAttempt(
                        PersonalMemoryKnowledgeQueryMode.ACTIVE, true, true, List.of(99L), 1L, "SUCCESS"));
        when(memoryMapper.selectList(any(Wrapper.class))).thenReturn(
                List.of(memory(99L, "user-1", "NOTE", "发布说明需要列出验证证据")),
                List.of(memory(1L, "user-1", "PREFERENCE", "请始终简洁回答")));

        PersonalMemoryService.QueryResult active = service.query(principal("user-1"),
                new QueryCommand("帮我准备发布说明", null, 5, 512, null, null, null));
        assertEquals(List.of(99L, 1L), active.hits().stream().map(PersonalMemoryService.QueryHit::id).toList());

        PersonalMemoryKnowledgeQueryClient.QueryAttempt shadow = new PersonalMemoryKnowledgeQueryClient.QueryAttempt(
                PersonalMemoryKnowledgeQueryMode.SHADOW, true, true, List.of(2L), 10L, "SUCCESS");
        when(queryClient.query("default", "user-1", "发布说明", 15, 512)).thenReturn(shadow);
        when(memoryMapper.selectList(any(Wrapper.class))).thenReturn(
                List.of(memory(1L, "user-1", "NOTE", "发布说明要包含验证证据"),
                        memory(2L, "user-1", "NOTE", "完全无关的纪念日")),
                List.of());

        PersonalMemoryService.QueryResult result = service.query(principal("user-1"),
                new QueryCommand("发布说明", null, 5, 512, null, null, null));
        assertEquals(List.of(1L), result.hits().stream().map(PersonalMemoryService.QueryHit::id).toList());
        verify(telemetry).record(shadow, List.of(1L), Set.of(1L, 2L), 5);
    }

    @Test
    void lifecycleDeletesExpiredMemories() {
        Fixture fixture = fixture();
        PersonalMemoryEntity expired = memory(9L, "user-1", "NOTE", "expired private content");
        expired.setExpiresAt(LocalDateTime.now().minusMinutes(1));
        PersonalMemoryEntity extended = memory(10L, "user-1", "NOTE", "extended");
        extended.setExpiresAt(LocalDateTime.now().plusDays(1));
        when(fixture.memoryMapper.selectList(any(Wrapper.class))).thenReturn(List.of(expired, extended));
        when(fixture.memoryMapper.selectByIdForUpdate(9L)).thenReturn(expired);
        when(fixture.memoryMapper.selectByIdForUpdate(10L)).thenReturn(extended);

        assertEquals(1, fixture.service.expireDue(50));

        verify(fixture.memoryMapper, times(1)).deleteById(any(Long.class));
        verify(fixture.memoryMapper).deleteById(9L);
        verify(fixture.outbox).enqueueDelete(any(), eq(9L), isNull());
        ArgumentCaptor<PersonalMemoryAuditEntity> audit = ArgumentCaptor.forClass(PersonalMemoryAuditEntity.class);
        verify(fixture.auditMapper).insert(audit.capture());
        assertEquals("EXPIRE", audit.getValue().getEventType());
        assertEquals("SYSTEM", audit.getValue().getActorType());
    }

    private static Fixture fixture() {
        PersonalMemoryMapper memoryMapper = mock(PersonalMemoryMapper.class);
        PersonalMemoryCandidateMapper candidateMapper = mock(PersonalMemoryCandidateMapper.class);
        PersonalMemoryAuditMapper auditMapper = mock(PersonalMemoryAuditMapper.class);
        PersonalMemoryIndexOutboxService outbox = mock(PersonalMemoryIndexOutboxService.class);
        PersonalMemoryService service = new PersonalMemoryService(memoryMapper, candidateMapper, auditMapper,
                new ObjectMapper(), outbox, null, null, 1000);
        return new Fixture(service, memoryMapper, candidateMapper, auditMapper, outbox);
    }

    static PersonalMemoryPrincipal principal(String userId) {
        return new PersonalMemoryPrincipal("default", userId, 1L, "platform-user", "platform-session");
    }

    static PersonalMemoryEntity memory(Long id, String userId, String type, String content) {
        PersonalMemoryEntity memory = new PersonalMemoryEntity();
        memory.setId(id);
        memory.setMemoryKey("pm-" + id);
        memory.setTenantId("default");
        memory.setOwnerId(userId);
        memory.setMemoryType(type);
        memory.setContent(content);
        memory.setContentSha256(PersonalMemoryService.contentHash(content));
        memory.setSourceType("MANUAL");
        memory.setCreatedAt(LocalDateTime.now());
        memory.setUpdatedAt(LocalDateTime.now());
        return memory;
    }

    private record Fixture(PersonalMemoryService service,
                           PersonalMemoryMapper memoryMapper,
                           PersonalMemoryCandidateMapper candidateMapper,
                           PersonalMemoryAuditMapper auditMapper,
                           PersonalMemoryIndexOutboxService outbox) {
    }
}
