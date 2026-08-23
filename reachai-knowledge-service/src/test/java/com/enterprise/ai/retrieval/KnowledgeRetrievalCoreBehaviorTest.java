package com.enterprise.ai.retrieval;

import com.enterprise.ai.client.ModelServiceClient;
import com.enterprise.ai.domain.entity.Chunk;
import com.enterprise.ai.domain.entity.FileInfo;
import com.enterprise.ai.domain.entity.KnowledgeBase;
import com.enterprise.ai.embedding.EmbeddingService;
import com.enterprise.ai.pipeline.document.artifact.DocumentArtifactStore;
import com.enterprise.ai.repository.ChunkRepository;
import com.enterprise.ai.repository.FileInfoRepository;
import com.enterprise.ai.repository.KnowledgeBaseRepository;
import com.enterprise.ai.repository.KnowledgeHitLogRepository;
import com.enterprise.ai.repository.KnowledgeQuestionRepository;
import com.enterprise.ai.repository.KnowledgeTagRepository;
import com.enterprise.ai.service.impl.KnowledgeServiceImpl;
import com.enterprise.ai.vector.VectorSearchRequest;
import com.enterprise.ai.vector.VectorSearchResult;
import com.enterprise.ai.vector.VectorService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import com.enterprise.ai.common.dto.ApiResult;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Real Core behavior tests through KnowledgeRetrievalCore + engine implementation.
 * Controller mock-forwarding alone is insufficient.
 */
class KnowledgeRetrievalCoreBehaviorTest {

    private KnowledgeBaseRepository knowledgeBaseRepository;
    private ChunkRepository chunkRepository;
    private FileInfoRepository fileInfoRepository;
    private EmbeddingService embeddingService;
    private VectorService vectorService;
    private ModelServiceClient modelServiceClient;
    private KnowledgeRetrievalCore core;

    @BeforeEach
    void setUp() {
        knowledgeBaseRepository = mock(KnowledgeBaseRepository.class);
        chunkRepository = mock(ChunkRepository.class);
        fileInfoRepository = mock(FileInfoRepository.class);
        embeddingService = mock(EmbeddingService.class);
        vectorService = mock(VectorService.class);
        modelServiceClient = mock(ModelServiceClient.class);
        KnowledgeServiceImpl engine = new KnowledgeServiceImpl(
                knowledgeBaseRepository,
                fileInfoRepository,
                chunkRepository,
                mock(KnowledgeTagRepository.class),
                mock(KnowledgeQuestionRepository.class),
                mock(KnowledgeHitLogRepository.class),
                embeddingService,
                modelServiceClient,
                vectorService,
                mock(DocumentArtifactStore.class));
        core = new KnowledgeRetrievalCore(engine);
    }

    @Test
    void vectorModeRespectsThresholdAndTopK() {
        KnowledgeBase kb = kb("kb_vec");
        when(knowledgeBaseRepository.selectList(any())).thenReturn(List.of(kb));
        when(embeddingService.embed(anyString(), anyString())).thenReturn(List.of(0.1f, 0.2f));
        when(vectorService.search(any(VectorSearchRequest.class))).thenReturn(List.of(
                result("v1", 0.9f, "f1", "high"),
                result("v2", 0.4f, "f1", "low"),
                result("v3", 0.8f, "f1", "mid")));
        Map<String, Chunk> byVector = Map.of(
                "v1", chunk(1L, kb.getId(), "f1", "high", "v1"),
                "v3", chunk(3L, kb.getId(), "f1", "mid", "v3"));
        when(chunkRepository.selectOne(ArgumentMatchers.<LambdaQueryWrapper<Chunk>>any())).thenAnswer(inv -> {
            // Prefer highest-score chunk materialization for this assertion.
            return byVector.getOrDefault("v1", byVector.get("v3"));
        });
        when(fileInfoRepository.selectList(any())).thenReturn(List.of(file("f1", "a.md")));

        KnowledgeRetrievalCoreResponse response = core.retrieve(KnowledgeRetrievalCoreRequest.builder()
                .query("refund")
                .knowledgeBaseCodes(List.of("kb_vec"))
                .searchMode("vector")
                .topK(1)
                .scoreThreshold(0.5f)
                .rerankEnabled(false)
                .recordHit(false)
                .userId("u1")
                .build());

        assertEquals(1, response.getItems().size());
        assertTrue(response.getItems().get(0).getScore() >= 0.5f);
        assertTrue(List.of("high", "mid").contains(response.getItems().get(0).getContent()));
    }

    @Test
    void keywordModeUsesContentMatchAndAclFileFilter() {
        KnowledgeBase kb = kb("kb_kw");
        when(knowledgeBaseRepository.selectList(any())).thenReturn(List.of(kb));
        Chunk allowed = chunk(11L, kb.getId(), "f1", "refund policy text", "k1");
        Chunk denied = chunk(12L, kb.getId(), "secret", "refund secret", "k2");
        when(chunkRepository.selectList(any())).thenReturn(List.of(allowed, denied));
        when(fileInfoRepository.selectList(any())).thenReturn(List.of(file("f1", "a.md")));

        KnowledgeRetrievalCoreResponse response = core.retrieve(KnowledgeRetrievalCoreRequest.builder()
                .query("refund")
                .knowledgeBaseCodes(List.of("kb_kw"))
                .searchMode("keyword")
                .topK(5)
                .scoreThreshold(0.1f)
                .rerankEnabled(false)
                .recordHit(false)
                .accessibleFileIds(List.of("f1"))
                .userId("u1")
                .build());

        assertEquals(1, response.getItems().size());
        assertEquals("f1", response.getItems().get(0).getFileId());
    }

    @Test
    void hybridDedupsSameChunkAndKeepsHigherSignal() {
        KnowledgeBase kb = kb("kb_hy");
        when(knowledgeBaseRepository.selectList(any())).thenReturn(List.of(kb));
        when(embeddingService.embed(anyString(), anyString())).thenReturn(List.of(0.1f));
        when(vectorService.search(any())).thenReturn(List.of(result("v1", 0.7f, "f1", "same content")));
        Chunk chunk = chunk(21L, kb.getId(), "f1", "same content", "v1");
        when(chunkRepository.selectOne(any())).thenReturn(chunk);
        when(chunkRepository.selectList(any())).thenReturn(List.of(chunk));
        when(fileInfoRepository.selectList(any())).thenReturn(List.of(file("f1", "a.md")));

        KnowledgeRetrievalCoreResponse response = core.retrieve(KnowledgeRetrievalCoreRequest.builder()
                .query("same")
                .knowledgeBaseCodes(List.of("kb_hy"))
                .searchMode("hybrid")
                .topK(5)
                .scoreThreshold(0.1f)
                .rerankEnabled(false)
                .recordHit(false)
                .userId("u1")
                .build());

        assertEquals(1, response.getItems().size());
        assertEquals("same content", response.getItems().get(0).getContent());
    }

    @Test
    void multiKnowledgeBaseFailureIsIsolated() {
        KnowledgeBase ok = kb("kb_ok");
        KnowledgeBase bad = kb("kb_bad");
        when(knowledgeBaseRepository.selectList(any())).thenReturn(List.of(ok, bad));
        AtomicInteger embeds = new AtomicInteger();
        when(embeddingService.embed(anyString(), anyString())).thenAnswer(inv -> {
            embeds.incrementAndGet();
            if ("kb_bad".equals(findKbCodeForModel(inv.getArgument(0)))) {
                throw new RuntimeException("embedding down");
            }
            return List.of(0.2f);
        });
        // Simpler: fail vectorService for one collection
        when(vectorService.search(any(VectorSearchRequest.class))).thenAnswer(inv -> {
            VectorSearchRequest req = inv.getArgument(0);
            if ("kb_bad".equals(req.getCollectionName())) {
                throw new RuntimeException("vector down");
            }
            return List.of(result("v-ok", 0.95f, "f1", "ok-hit"));
        });
        stubChunkLookup(ok.getId(), "v-ok", "f1", "ok-hit", 31L);
        when(chunkRepository.selectList(any())).thenReturn(List.of());
        when(fileInfoRepository.selectList(any())).thenReturn(List.of(file("f1", "a.md")));

        KnowledgeRetrievalCoreResponse response = core.retrieve(KnowledgeRetrievalCoreRequest.builder()
                .query("q")
                .knowledgeBaseCodes(List.of("kb_ok", "kb_bad"))
                .searchMode("vector")
                .topK(5)
                .scoreThreshold(0.1f)
                .rerankEnabled(false)
                .recordHit(false)
                .userId("u1")
                .build());

        assertEquals(1, response.getItems().size());
        assertEquals("ok-hit", response.getItems().get(0).getContent());
    }

    @Test
    void rerankFlagFalseSkipsModelRerankPath() {
        KnowledgeBase kb = kb("kb_rr");
        kb.setRerankEnabled(true);
        kb.setRerankModelInstanceId("rerank-1");
        when(knowledgeBaseRepository.selectList(any())).thenReturn(List.of(kb));
        when(embeddingService.embed(anyString(), anyString())).thenReturn(List.of(0.1f));
        when(vectorService.search(any())).thenReturn(List.of(result("v1", 0.9f, "f1", "content")));
        stubChunkLookup(kb.getId(), "v1", "f1", "content", 41L);
        when(fileInfoRepository.selectList(any())).thenReturn(List.of(file("f1", "a.md")));

        KnowledgeRetrievalCoreResponse response = core.retrieve(KnowledgeRetrievalCoreRequest.builder()
                .query("q")
                .knowledgeBaseCodes(List.of("kb_rr"))
                .searchMode("vector")
                .topK(3)
                .scoreThreshold(0.1f)
                .rerankEnabled(false)
                .recordHit(false)
                .userId("u1")
                .build());

        assertEquals(1, response.getItems().size());
        assertNull(response.getItems().get(0).getRerankScore());
        assertTrue(response.getItems().stream().allMatch(item -> item.getRerankScore() == null));
        verify(modelServiceClient, never()).rerank(any(ModelServiceClient.RerankParam.class));
    }

    @Test
    void rerankFlagTrueCallsModelOnceAndChangesOrder() {
        KnowledgeBase kb = kb("kb_rr_on");
        kb.setRerankEnabled(true);
        kb.setRerankModelInstanceId("rerank-1");
        when(knowledgeBaseRepository.selectList(any())).thenReturn(List.of(kb));
        when(embeddingService.embed(anyString(), anyString())).thenReturn(List.of(0.1f));
        when(vectorService.search(any())).thenReturn(List.of(
                result("v1", 0.95f, "f1", "first"),
                result("v2", 0.90f, "f1", "second")));
        Chunk c1 = chunk(51L, kb.getId(), "f1", "first", "v1");
        Chunk c2 = chunk(52L, kb.getId(), "f1", "second", "v2");
        when(chunkRepository.selectOne(ArgumentMatchers.<LambdaQueryWrapper<Chunk>>any())).thenAnswer(inv -> c1);
        // keyword path unused; vector materialization uses selectOne — return matching by sequential calls
        AtomicInteger lookups = new AtomicInteger();
        when(chunkRepository.selectOne(ArgumentMatchers.<LambdaQueryWrapper<Chunk>>any())).thenAnswer(inv ->
                lookups.getAndIncrement() == 0 ? c1 : c2);
        when(fileInfoRepository.selectList(any())).thenReturn(List.of(file("f1", "a.md")));
        when(modelServiceClient.rerank(any())).thenReturn(ApiResult.ok(new ModelServiceClient.RerankResult(
                "rerank-1",
                "mock",
                List.of(
                        new ModelServiceClient.RerankItem(1, 0.99f, "second"),
                        new ModelServiceClient.RerankItem(0, 0.10f, "first")))));

        KnowledgeRetrievalCoreResponse response = core.retrieve(KnowledgeRetrievalCoreRequest.builder()
                .query("q")
                .knowledgeBaseCodes(List.of("kb_rr_on"))
                .searchMode("vector")
                .topK(2)
                .scoreThreshold(0.01f)
                .rerankEnabled(true)
                .recordHit(false)
                .userId("u1")
                .build());

        verify(modelServiceClient, times(1)).rerank(any(ModelServiceClient.RerankParam.class));
        assertEquals(2, response.getItems().size());
        assertEquals("second", response.getItems().get(0).getContent());
        assertEquals("first", response.getItems().get(1).getContent());
        assertEquals(0.99f, response.getItems().get(0).getRerankScore());
        assertEquals(0.10f, response.getItems().get(1).getRerankScore());
    }

    @Test
    void vectorRequestCarriesTopKThresholdAndAclFilter() {
        KnowledgeBase kb = kb("kb_filter");
        when(knowledgeBaseRepository.selectList(any())).thenReturn(List.of(kb));
        when(embeddingService.embed(anyString(), anyString())).thenReturn(List.of(0.1f));
        // Vector layer has no scoreThreshold field; engine applies scoreThreshold=0.33 after search.
        when(vectorService.search(any(VectorSearchRequest.class))).thenReturn(List.of(
                result("v-below", 0.32f, "f1", "below-threshold"),
                result("v1", 0.33f, "f1", "threshold-hit"),
                result("v-above", 0.90f, "f1", "above")));
        Chunk hit = chunk(61L, kb.getId(), "f1", "threshold-hit", "v1");
        Chunk above = chunk(62L, kb.getId(), "f1", "above", "v-above");
        // Engine drops score < 0.33 before chunk materialization, so lookups are only for kept hits.
        AtomicInteger lookups = new AtomicInteger();
        when(chunkRepository.selectOne(ArgumentMatchers.<LambdaQueryWrapper<Chunk>>any())).thenAnswer(inv ->
                lookups.getAndIncrement() == 0 ? hit : above);
        when(fileInfoRepository.selectList(any())).thenReturn(List.of());

        KnowledgeRetrievalCoreResponse response = core.retrieve(KnowledgeRetrievalCoreRequest.builder()
                .query("refund")
                .knowledgeBaseCodes(List.of("kb_filter"))
                .searchMode("vector")
                .topK(7)
                .scoreThreshold(0.33f)
                .rerankEnabled(false)
                .recordHit(false)
                .accessibleFileIds(List.of("f1", "f2"))
                .fileIdFilterExpression("file_id in [\"f1\",\"f2\"]")
                .userId("u1")
                .build());

        ArgumentCaptor<VectorSearchRequest> captor = ArgumentCaptor.forClass(VectorSearchRequest.class);
        verify(vectorService).search(captor.capture());
        VectorSearchRequest req = captor.getValue();
        // Vector search topK is the requested topK (no overfetch). Keyword mode overfetches by topK*3.
        assertEquals(7, req.getTopK());
        assertEquals("file_id in [\"f1\",\"f2\"]", req.getFilterExpression());
        // Post-search threshold 0.33 drops 0.32; keeps 0.33 and 0.90; topK=7 does not truncate further.
        assertEquals(2, response.getItems().size());
        assertTrue(response.getItems().stream().noneMatch(item -> "below-threshold".equals(item.getContent())));
        assertEquals(0.33f, response.getItems().stream()
                .filter(item -> "threshold-hit".equals(item.getContent()))
                .findFirst()
                .orElseThrow()
                .getScore());
    }

    private void stubChunkLookup(Long kbId, String vectorId, String fileId, String content, Long dbId) {
        Chunk chunk = chunk(dbId, kbId, fileId, content, vectorId);
        when(chunkRepository.selectOne(ArgumentMatchers.<LambdaQueryWrapper<Chunk>>any())).thenAnswer(inv -> {
            // Return matching chunk for any selectOne in this test scope.
            return chunk;
        });
    }

    private static String findKbCodeForModel(String modelId) {
        return modelId;
    }

    private static KnowledgeBase kb(String code) {
        KnowledgeBase kb = new KnowledgeBase();
        kb.setId((long) Math.abs(code.hashCode() % 10_000) + 1L);
        kb.setCode(code);
        kb.setStatus(1);
        kb.setEmbeddingModelInstanceId("emb-" + code);
        kb.setVectorWeight(0.7f);
        kb.setKeywordWeight(0.3f);
        kb.setRerankEnabled(false);
        kb.setSearchMode("hybrid");
        return kb;
    }

    private static Chunk chunk(Long id, Long kbId, String fileId, String content, String vectorId) {
        Chunk chunk = new Chunk();
        chunk.setId(id);
        chunk.setKnowledgeBaseId(kbId);
        chunk.setFileId(fileId);
        chunk.setContent(content);
        chunk.setVectorId(vectorId);
        chunk.setEnabled(1);
        chunk.setHitCount(0);
        chunk.setChunkIndex(0);
        return chunk;
    }

    private static FileInfo file(String id, String name) {
        FileInfo file = new FileInfo();
        file.setFileId(id);
        file.setFileName(name);
        return file;
    }

    private static VectorSearchResult result(String id, float score, String fileId, String content) {
        return VectorSearchResult.builder()
                .id(id)
                .score(score)
                .fields(Map.of("file_id", fileId, "content", content))
                .build();
    }
}
