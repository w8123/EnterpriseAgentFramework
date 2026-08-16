package com.enterprise.ai.personalmemory;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.enterprise.ai.embedding.EmbeddingService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SuppressWarnings("unchecked")
class PersonalMemoryIndexServiceTest {

    @BeforeAll
    static void metadata() {
        Configuration configuration = new Configuration();
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(configuration, "pm-index"),
                KnowledgePersonalMemoryIndexEntity.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(configuration, "pm-index-event"),
                KnowledgePersonalMemoryIndexEventEntity.class);
    }

    @Test
    void appliesUpsertWithoutPersistingRawRuntimeUserId() {
        Fixture fixture = fixture();
        when(fixture.eventMapper.selectOne(any(Wrapper.class))).thenReturn(null);
        when(fixture.indexMapper.updateIfNewer(any())).thenReturn(0);

        var result = fixture.service.apply(event("e1", "PERSONAL_MEMORY_UPSERT", 1000, "请默认中文回答"));

        assertEquals("APPLIED", result.status());
        ArgumentCaptor<KnowledgePersonalMemoryIndexEntity> saved =
                ArgumentCaptor.forClass(KnowledgePersonalMemoryIndexEntity.class);
        verify(fixture.indexMapper).insert(saved.capture());
        assertEquals(64, saved.getValue().getRuntimeUserHash().length());
        assertFalse(saved.getValue().getRuntimeUserHash().contains("user-1"));
        assertEquals("ACTIVE", saved.getValue().getStatus());
        assertEquals("DISABLED", saved.getValue().getEmbeddingStatus());
    }

    @Test
    void ignoresOutOfOrderOlderEvent() {
        Fixture fixture = fixture();
        when(fixture.eventMapper.selectOne(any(Wrapper.class))).thenReturn(null);
        when(fixture.indexMapper.updateIfNewer(any())).thenReturn(0);
        when(fixture.indexMapper.insert(any())).thenThrow(new DuplicateKeyException("newer row exists"));

        var result = fixture.service.apply(event("e-old", "PERSONAL_MEMORY_UPSERT", 1000, "旧值"));

        assertEquals("IGNORED_STALE", result.status());
        verify(fixture.indexMapper, org.mockito.Mockito.times(2)).updateIfNewer(any());
    }

    @Test
    void deleteScrubsProjectionAndTombstonesMissingRows() {
        Fixture fixture = fixture();
        when(fixture.eventMapper.selectOne(any(Wrapper.class))).thenReturn(null);
        when(fixture.indexMapper.updateIfNewer(any())).thenReturn(0);

        var result = fixture.service.apply(event("e-delete", "PERSONAL_MEMORY_DELETE", 3000, null));

        assertEquals("APPLIED", result.status());
        ArgumentCaptor<KnowledgePersonalMemoryIndexEntity> saved =
                ArgumentCaptor.forClass(KnowledgePersonalMemoryIndexEntity.class);
        verify(fixture.indexMapper).insert(saved.capture());
        assertEquals("DELETED", saved.getValue().getStatus());
        assertEquals("[deleted]", saved.getValue().getContent());
        assertEquals("DELETED", saved.getValue().getEmbeddingStatus());
        assertNull(saved.getValue().getEmbeddingVector());
        assertNull(saved.getValue().getEmbeddingClaimToken());
    }

    @Test
    void eventReplayIsIdempotent() {
        Fixture fixture = fixture();
        KnowledgePersonalMemoryIndexEventEntity applied = new KnowledgePersonalMemoryIndexEventEntity();
        applied.setEventId("e1");
        applied.setStatus("APPLIED");
        when(fixture.eventMapper.selectOne(any(Wrapper.class))).thenReturn(applied);

        var result = fixture.service.apply(event("e1", "PERSONAL_MEMORY_UPSERT", 1000, "内容"));

        assertTrue(result.idempotentReplay());
        verify(fixture.indexMapper, never()).selectOne(any());
    }

    @Test
    void queryReturnsCandidateIdsWithoutProjectionContent() throws Exception {
        Fixture fixture = fixture();
        KnowledgePersonalMemoryIndexEntity projection = new KnowledgePersonalMemoryIndexEntity();
        projection.setMemoryId(9L);
        projection.setTenantId("default");
        projection.setRuntimeUserHash("not-returned");
        projection.setItemType("PREFERENCE");
        projection.setTitle("语言偏好");
        projection.setContent("请默认中文回答");
        projection.setSummary("不应离开 Knowledge");
        projection.setTrustLevel("VERIFIED");
        projection.setStatus("ACTIVE");
        projection.setUpdatedAt(LocalDateTime.now());
        when(fixture.indexMapper.selectList(any(Wrapper.class))).thenReturn(List.of(projection));

        var result = fixture.service.query(
                new PersonalMemoryIndexService.QueryRequest("default", "user-1", "中文", 5, 1_000));
        String json = new ObjectMapper().writeValueAsString(result);

        assertEquals(1, result.hits().size());
        assertEquals(9L, result.hits().get(0).memoryId());
        assertFalse(json.contains("请默认中文回答"));
        assertFalse(json.contains("title"));
        assertFalse(json.contains("content"));
        assertFalse(json.contains("summary"));
        assertFalse(json.contains("trustLevel"));
        assertFalse(json.contains("score"));
    }

    @Test
    void ownerStatusReturnsOnlyPseudonymousAggregateErasureProof() throws Exception {
        Fixture fixture = fixture();
        PersonalMemoryOwnerProjectionStatusRow row = new PersonalMemoryOwnerProjectionStatusRow();
        row.setTotalCount(3L);
        row.setActiveCount(0L);
        row.setDeletedCount(3L);
        row.setUnsafeDeletedVectorCount(0L);
        row.setMaxSourceVersion(27L);
        row.setLatestUpdatedAt(LocalDateTime.now());
        when(fixture.indexMapper.selectOwnerProjectionStatus(eq("default"), any()))
                .thenReturn(row);

        var result = fixture.service.ownerStatus(
                new PersonalMemoryIndexService.OwnerStatusRequest("DEFAULT", "runtime-user-secret"));
        String json = new ObjectMapper().findAndRegisterModules().writeValueAsString(result);

        assertTrue(result.projectionErased());
        assertEquals(3, result.totalCount());
        assertEquals(0, result.activeCount());
        assertEquals(3, result.deletedCount());
        assertEquals(64, result.runtimeUserHash().length());
        assertFalse(json.contains("runtime-user-secret"));
        verify(fixture.indexMapper).selectOwnerProjectionStatus(
                eq("default"), eq(result.runtimeUserHash()));
    }

    @Test
    void vectorModeFindsSemanticMatchWithoutLexicalOverlap() {
        Fixture fixture = fixture(PersonalMemoryEmbeddingProperties.Mode.VECTOR);
        KnowledgePersonalMemoryIndexEntity semanticMatch = readyProjection(
                9L, "用户喜欢简洁的答复", List.of(1f, 0f), "memory-embedding", 10L);
        KnowledgePersonalMemoryIndexEntity unrelated = readyProjection(
                10L, "这是另一条记录", List.of(0f, 1f), "memory-embedding", 11L);
        when(fixture.indexMapper.selectList(any(Wrapper.class)))
                .thenReturn(List.of(unrelated, semanticMatch));
        when(fixture.embeddingService.embed("memory-embedding", "回答不要太啰嗦"))
                .thenReturn(List.of(.95f, .05f));

        var result = fixture.service.query(new PersonalMemoryIndexService.QueryRequest(
                "default", "user-1", "回答不要太啰嗦", 5, 1_000));

        assertEquals(List.of(9L), result.hits().stream()
                .map(PersonalMemoryIndexService.QueryHit::memoryId).toList());
    }

    @Test
    void vectorModeRejectsStaleWrongModelAndUnknownFormatProjections() {
        Fixture fixture = fixture(PersonalMemoryEmbeddingProperties.Mode.VECTOR);
        KnowledgePersonalMemoryIndexEntity stale = readyProjection(
                1L, "stale", List.of(1f, 0f), "memory-embedding", 20L);
        stale.setEmbeddingSourceVersion(19L);
        KnowledgePersonalMemoryIndexEntity wrongModel = readyProjection(
                2L, "wrong model", List.of(1f, 0f), "old-model", 20L);
        KnowledgePersonalMemoryIndexEntity wrongFormat = readyProjection(
                3L, "wrong format", List.of(1f, 0f), "memory-embedding", 20L);
        wrongFormat.setEmbeddingFormat("UNKNOWN");
        when(fixture.indexMapper.selectList(any(Wrapper.class)))
                .thenReturn(List.of(stale, wrongModel, wrongFormat));
        when(fixture.embeddingService.embed(any(), any())).thenReturn(List.of(1f, 0f));

        var result = fixture.service.query(new PersonalMemoryIndexService.QueryRequest(
                "default", "user-1", "semantic query", 5, 1_000));

        assertTrue(result.hits().isEmpty());
    }

    @Test
    void hybridModeUsesLexicalFallbackWhileEmbeddingIsPending() {
        Fixture fixture = fixture(PersonalMemoryEmbeddingProperties.Mode.HYBRID);
        KnowledgePersonalMemoryIndexEntity pending = projection(7L, "请默认中文回答", 7L);
        pending.setEmbeddingStatus("PENDING");
        when(fixture.indexMapper.selectList(any(Wrapper.class))).thenReturn(List.of(pending));
        when(fixture.embeddingService.embed(any(), any())).thenReturn(List.of(1f, 0f));

        var result = fixture.service.query(new PersonalMemoryIndexService.QueryRequest(
                "default", "user-1", "中文", 5, 1_000));

        assertEquals(7L, result.hits().get(0).memoryId());
    }

    @Test
    void semanticProviderFailureIsVisibleToTheControlFallbackBoundary() {
        Fixture fixture = fixture(PersonalMemoryEmbeddingProperties.Mode.HYBRID);
        when(fixture.indexMapper.selectList(any(Wrapper.class)))
                .thenReturn(List.of(readyProjection(
                        7L, "中文", List.of(1f, 0f), "memory-embedding", 7L)));
        when(fixture.embeddingService.embed(any(), any()))
                .thenThrow(new IllegalStateException("provider unavailable"));

        assertThrows(IllegalStateException.class, () -> fixture.service.query(
                new PersonalMemoryIndexService.QueryRequest("default", "user-1", "中文", 5, 1_000)));
    }

    @Test
    void semanticQueryIsBoundedWithoutSplittingASurrogatePair() {
        Fixture fixture = fixture(PersonalMemoryEmbeddingProperties.Mode.VECTOR);
        fixture.properties.setMaxQueryChars(128);
        when(fixture.indexMapper.selectList(any(Wrapper.class))).thenReturn(List.of(
                readyProjection(7L, "semantic", List.of(1f, 0f), "memory-embedding", 7L)));
        when(fixture.embeddingService.embed(any(), any())).thenReturn(List.of(1f, 0f));
        String oversized = "a".repeat(127) + "😀" + "tail";

        fixture.service.query(new PersonalMemoryIndexService.QueryRequest(
                "default", "user-1", oversized, 5, 1_000));

        ArgumentCaptor<String> sent = ArgumentCaptor.forClass(String.class);
        verify(fixture.embeddingService).embed(eq("memory-embedding"), sent.capture());
        assertEquals(127, sent.getValue().length());
        assertEquals("a".repeat(127), sent.getValue());
    }

    private static PersonalMemoryIndexService.IndexEvent event(
            String eventId, String type, long version, String content) {
        String payload = "{\"schema\":\"reachai-personal-memory-index-event-v1\"," +
                "\"memoryId\":9,\"tenantId\":\"default\",\"runtimeUserId\":\"user-1\"," +
                "\"sourceVersion\":" + version +
                (content == null ? "" : ",\"type\":\"PREFERENCE\",\"content\":\"" + content +
                        "\",\"trustLevel\":\"VERIFIED\"") + "}";
        return new PersonalMemoryIndexService.IndexEvent(eventId, type, payload);
    }

    private static Fixture fixture() {
        return fixture(PersonalMemoryEmbeddingProperties.Mode.LEXICAL);
    }

    private static Fixture fixture(PersonalMemoryEmbeddingProperties.Mode mode) {
        KnowledgePersonalMemoryIndexMapper indexMapper = mock(KnowledgePersonalMemoryIndexMapper.class);
        KnowledgePersonalMemoryIndexEventMapper eventMapper = mock(KnowledgePersonalMemoryIndexEventMapper.class);
        EmbeddingService embeddingService = mock(EmbeddingService.class);
        PersonalMemoryEmbeddingProperties properties = new PersonalMemoryEmbeddingProperties();
        properties.setMode(mode);
        if (mode != PersonalMemoryEmbeddingProperties.Mode.LEXICAL) {
            properties.setModelInstanceId("memory-embedding");
        }
        properties.afterPropertiesSet();
        PersonalMemoryIndexIdentity identity = new PersonalMemoryIndexIdentity("test-index-identity-secret");
        return new Fixture(new PersonalMemoryIndexService(indexMapper, eventMapper, identity,
                new ObjectMapper(), properties, embeddingService), indexMapper, eventMapper,
                embeddingService, properties);
    }

    private static KnowledgePersonalMemoryIndexEntity readyProjection(
            Long id, String content, List<Float> vector, String model, Long sourceVersion) {
        KnowledgePersonalMemoryIndexEntity entity = projection(id, content, sourceVersion);
        entity.setEmbeddingVector(PersonalMemoryEmbeddingCodec.encode(vector));
        entity.setEmbeddingFormat(PersonalMemoryEmbeddingCodec.FORMAT);
        entity.setEmbeddingDimension(vector.size());
        entity.setEmbeddingModelInstanceId(model);
        entity.setEmbeddingSourceVersion(sourceVersion);
        entity.setEmbeddingTextSha256("a".repeat(64));
        entity.setEmbeddingStatus("READY");
        return entity;
    }

    private static KnowledgePersonalMemoryIndexEntity projection(Long id, String content, Long sourceVersion) {
        KnowledgePersonalMemoryIndexEntity entity = new KnowledgePersonalMemoryIndexEntity();
        entity.setId(id);
        entity.setMemoryId(id);
        entity.setTenantId("default");
        entity.setRuntimeUserHash("owner-hash");
        entity.setItemType("PREFERENCE");
        entity.setContent(content);
        entity.setTrustLevel("VERIFIED");
        entity.setSourceVersion(sourceVersion);
        entity.setStatus("ACTIVE");
        entity.setUpdatedAt(LocalDateTime.now());
        return entity;
    }

    private record Fixture(PersonalMemoryIndexService service,
                           KnowledgePersonalMemoryIndexMapper indexMapper,
                           KnowledgePersonalMemoryIndexEventMapper eventMapper,
                           EmbeddingService embeddingService,
                           PersonalMemoryEmbeddingProperties properties) {}
}
