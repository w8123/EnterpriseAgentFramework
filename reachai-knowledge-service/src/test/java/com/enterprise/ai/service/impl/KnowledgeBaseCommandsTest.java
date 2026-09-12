package com.enterprise.ai.service.impl;

import com.enterprise.ai.domain.dto.KbConfigRequest;
import com.enterprise.ai.domain.dto.KnowledgeBaseRequest;
import com.enterprise.ai.domain.entity.KnowledgeBase;
import com.enterprise.ai.repository.*;
import com.enterprise.ai.retrieval.KnowledgeRetrievalEngine;
import com.enterprise.ai.support.KnowledgeQueryTestDatabase;
import com.enterprise.ai.vector.VectorService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class KnowledgeBaseCommandsTest {
    private KnowledgeQueryTestDatabase db;
    private KnowledgePublicationMysqlDatabase mysql;
    private KnowledgeServiceImpl service;
    private TransactionTemplate transaction;
    private ExecutorService competingConnection;
    private final AtomicReference<Runnable> afterRead = new AtomicReference<>();

    protected boolean useMysql() { return false; }

    @BeforeEach
    void setUp() throws Exception {
        if (useMysql()) {
            mysql = KnowledgePublicationMysqlDatabase.withBaseCommands(10);
            try (var connection = mysql.getConnection()) {
                assertEquals(java.sql.Connection.TRANSACTION_REPEATABLE_READ, connection.getTransactionIsolation());
            }
            db = new KnowledgeQueryTestDatabase(mysql, KnowledgeBaseRepository.class);
        } else {
            db = new KnowledgeQueryTestDatabase(List.of("knowledge_base"), KnowledgeBaseRepository.class);
        }
        competingConnection = Executors.newSingleThreadExecutor();
        var realBases = db.mapper(KnowledgeBaseRepository.class);
        var bases = mock(KnowledgeBaseRepository.class, delegatesTo(realBases));
        doAnswer(invocation -> {
            KnowledgeBase snapshot = realBases.selectOne(invocation.getArgument(0));
            Runnable change = afterRead.getAndSet(null);
            if (change != null) competingConnection.submit(change).get(5, TimeUnit.SECONDS);
            return snapshot;
        }).when(bases).selectOne(any());
        var manager = new DataSourceTransactionManager(db.jdbc().getDataSource());
        transaction = new TransactionTemplate(manager);
        var tags = mock(KnowledgeTagRepository.class);
        var questions = mock(KnowledgeQuestionService.class);
        var deletion = mock(KnowledgeFileDeletionService.class);
        var lifecycle = new KnowledgeBaseLifecycleService(bases, tags, questions, deletion,
                mock(KnowledgeCollectionLifecycleStore.class), mock(VectorService.class), manager);
        service = KnowledgeManagementTestServices.create(mock(FileInfoRepository.class), mock(ChunkRepository.class), tags,
                questions, mock(KnowledgeOperationsQuery.class), mock(KnowledgeIndexWriteService.class),
                new KnowledgeBaseLookup(bases), mock(KnowledgeRetrievalEngine.class), deletion, lifecycle,
                mock(KnowledgeTagService.class));
        db.jdbc().update("INSERT INTO knowledge_base(id,name,code,vector_collection_name,description,"
                + "embedding_model_instance_id,llm_model_instance_id,chunk_size,chunk_overlap,split_type,"
                + "status,search_mode,top_k) VALUES (7,'Original','kb','collection-a','Original description',"
                + "'embedding-a','llm-a',800,100,'FIXED',1,'hybrid',5)");
    }

    @AfterEach
    void tearDown() throws Exception {
        if (competingConnection != null) {
            competingConnection.shutdownNow();
            assertTrue(competingConnection.awaitTermination(5, TimeUnit.SECONDS));
        }
        try {
            if (db != null) db.close();
        } finally {
            if (mysql != null) mysql.close();
        }
    }

    @Test
    void configurationUpdatePreservesConcurrentCatalogChanges() {
        afterRead.set(() -> db.jdbc().update("UPDATE knowledge_base SET name='Concurrent name',"
                + "embedding_model_instance_id='embedding-b',status=0 WHERE id=7"));
        KbConfigRequest request = new KbConfigRequest();
        request.setChunkSize(1200);
        transaction.executeWithoutResult(ignored -> service.updateKbConfig("kb", request));
        Map<String, Object> row = row();
        assertAll(
                () -> assertEquals("Concurrent name", row.get("name")),
                () -> assertEquals("embedding-b", row.get("embedding_model_instance_id")),
                () -> assertEquals(0, ((Number) row.get("status")).intValue()),
                () -> assertEquals(1200, ((Number) row.get("chunk_size")).intValue()));
    }

    @Test
    void catalogUpdatePreservesConcurrentChunkingAndSearchChanges() {
        afterRead.set(() -> db.jdbc().update("UPDATE knowledge_base SET chunk_size=1600,top_k=15 WHERE id=7"));
        KnowledgeBaseRequest request = catalogRequest();
        transaction.executeWithoutResult(ignored -> service.update(request));
        Map<String, Object> row = row();
        assertAll(
                () -> assertEquals("Edited", row.get("name")),
                () -> assertEquals(1600, ((Number) row.get("chunk_size")).intValue()),
                () -> assertEquals(15, ((Number) row.get("top_k")).intValue()));
    }

    @Test
    void deletedKnowledgeBaseIsNotReportedAsUpdated() {
        afterRead.set(() -> db.jdbc().update("DELETE FROM knowledge_base WHERE id=7"));
        KbConfigRequest request = new KbConfigRequest();
        request.setChunkSize(1400);
        assertThrows(IllegalStateException.class,
                () -> transaction.executeWithoutResult(ignored -> service.updateKbConfig("kb", request)));
        assertEquals(0, db.jdbc().queryForObject("SELECT COUNT(*) FROM knowledge_base", Integer.class));
    }

    @Test
    void reusedRowIdCannotRedirectACatalogEditToAnotherCollectionGeneration() {
        afterRead.set(() -> {
            db.jdbc().update("DELETE FROM knowledge_base WHERE id=7");
            db.jdbc().update("INSERT INTO knowledge_base(id,name,code,vector_collection_name)"
                    + " VALUES (7,'Replacement','kb','collection-b')");
        });
        assertAll(
                () -> assertThrows(IllegalStateException.class,
                        () -> transaction.executeWithoutResult(ignored -> service.update(catalogRequest()))),
                () -> assertEquals("Replacement", row().get("name")),
                () -> assertEquals("collection-b", row().get("vector_collection_name")));
    }

    @Test
    void ordinaryUpdatesRetainOmittedSettingsAndNormalizeProvidedSearchMode() {
        KnowledgeBaseRequest catalog = catalogRequest();
        catalog.setSearchMode("keywords");
        catalog.setEmbeddingModelInstanceId(" embedding-c ");
        transaction.executeWithoutResult(ignored -> service.update(catalog));
        KbConfigRequest config = new KbConfigRequest();
        config.setChunkOverlap(0);
        config.setDirectReturnEnabled(false);
        transaction.executeWithoutResult(ignored -> service.updateKbConfig("kb", config));
        transaction.executeWithoutResult(ignored -> service.updateKbConfig("kb", config));
        Map<String, Object> row = row();
        assertAll(
                () -> assertEquals("keyword", row.get("search_mode")),
                () -> assertEquals("embedding-c", row.get("embedding_model_instance_id")),
                () -> assertEquals(800, ((Number) row.get("chunk_size")).intValue()),
                () -> assertEquals(0, ((Number) row.get("chunk_overlap")).intValue()),
                () -> assertFalse(db.jdbc().queryForObject("SELECT direct_return_enabled FROM knowledge_base WHERE id=7", Boolean.class)),
                () -> assertEquals("collection-a", row.get("vector_collection_name")));
    }

    private Map<String, Object> row() {
        return db.jdbc().queryForMap("SELECT * FROM knowledge_base WHERE id=7");
    }

    private KnowledgeBaseRequest catalogRequest() {
        KnowledgeBaseRequest request = new KnowledgeBaseRequest();
        request.setCode("kb");
        request.setName("Edited");
        request.setEmbeddingModelInstanceId("embedding-a");
        request.setLlmModelInstanceId("llm-a");
        return request;
    }
}
