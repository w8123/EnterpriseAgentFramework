package com.enterprise.ai.service.impl;

import com.enterprise.ai.domain.dto.KnowledgeBaseRequest;
import com.enterprise.ai.embedding.EmbeddingService;
import com.enterprise.ai.repository.*;
import com.enterprise.ai.support.KnowledgeQueryTestDatabase;
import com.enterprise.ai.vector.VectorService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class KnowledgeBaseDimensionTest {
    private KnowledgeQueryTestDatabase db;
    private EmbeddingService embeddings;
    private VectorService vectors;
    private KnowledgeBaseLifecycleService lifecycle;

    @BeforeEach
    void setup() throws Exception {
        db = new KnowledgeQueryTestDatabase(List.of("knowledge_base", "knowledge_chunk", "knowledge_collection_lifecycle"),
                KnowledgeBaseRepository.class, ChunkRepository.class, KnowledgeCollectionLifecycleRepository.class);
        embeddings = mock(EmbeddingService.class);
        vectors = mock(VectorService.class);
        lifecycle = new KnowledgeBaseLifecycleService(db.mapper(KnowledgeBaseRepository.class),
                mock(KnowledgeTagRepository.class), mock(KnowledgeQuestionService.class),
                mock(KnowledgeFileDeletionService.class), KnowledgeIndexTestSupport.collectionStore(db),
                vectors, embeddings, new DataSourceTransactionManager(db.jdbc().getDataSource()));
    }

    @AfterEach
    void close() { if (db != null) db.close(); }

    @Test
    void missingDimensionUsesTheSelectedModelsActualVectorBeforeCreatingAnyCollection() {
        when(embeddings.embed(eq("embedding-selected"), anyString())).thenAnswer(call -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            assertNoPublishedOrPendingCollection();
            verifyNoInteractions(vectors);
            assertEquals("ReachAI collection dimension probe", call.getArgument(1));
            return List.of(0.1f, 0.2f, 0.3f);
        });
        lifecycle.create(request());
        var base = db.mapper(KnowledgeBaseRepository.class).selectOne(null);
        assertEquals(3, base.getDimension());
        assertEquals("embedding-selected", base.getEmbeddingModelInstanceId());
        verify(vectors).ensureCollection(base.getVectorCollectionName(), 3);
        assertEquals("READY", db.jdbc().queryForObject("SELECT state FROM knowledge_collection_lifecycle", String.class));
    }

    @Test
    void explicitDimensionPreservesTheApiContractWithoutAnExtraProviderCall() {
        var request = request();
        request.setDimension(1024);
        lifecycle.create(request);
        var base = db.mapper(KnowledgeBaseRepository.class).selectOne(null);
        assertEquals(1024, base.getDimension());
        verify(vectors).ensureCollection(base.getVectorCollectionName(), 1024);
        verifyNoInteractions(embeddings);
    }

    @Test
    void providerFailureDoesNotPublishMetadataOrRegisterCleanupWork() {
        var failure = new IllegalStateException("Embedding provider unavailable");
        when(embeddings.embed(eq("embedding-selected"), anyString())).thenThrow(failure);
        assertSame(failure, assertThrows(IllegalStateException.class, () -> lifecycle.create(request())));
        assertNoPublishedOrPendingCollection();
        verifyNoInteractions(vectors);
    }

    @Test
    void missingEmptyOrNonFiniteVectorsFailBeforeAnyCollectionSideEffect() {
        for (List<Float> vector : Arrays.asList(null, List.<Float>of(), Arrays.asList(0.1f, null),
                List.of(Float.NaN), List.of(Float.POSITIVE_INFINITY))) {
            when(embeddings.embed(eq("embedding-selected"), anyString())).thenReturn(vector);
            assertThrows(IllegalArgumentException.class, () -> lifecycle.create(request()));
            assertNoPublishedOrPendingCollection();
            verifyNoInteractions(vectors);
        }
    }

    @Test
    void invalidExplicitDimensionFailsWithoutCallingAnyProvider() {
        var request = request();
        request.setDimension(0);
        assertThrows(IllegalArgumentException.class, () -> lifecycle.create(request));
        assertNoPublishedOrPendingCollection();
        verifyNoInteractions(vectors, embeddings);
    }

    private void assertNoPublishedOrPendingCollection() {
        assertEquals(0, db.jdbc().queryForObject("SELECT COUNT(*) FROM knowledge_base", Integer.class));
        assertEquals(0, db.jdbc().queryForObject("SELECT COUNT(*) FROM knowledge_collection_lifecycle", Integer.class));
    }

    private KnowledgeBaseRequest request() {
        var request = new KnowledgeBaseRequest();
        request.setCode("dimension-probe");
        request.setName("维度探测实验");
        request.setEmbeddingModelInstanceId("embedding-selected");
        request.setLlmModelInstanceId("llm-selected");
        return request;
    }
}
