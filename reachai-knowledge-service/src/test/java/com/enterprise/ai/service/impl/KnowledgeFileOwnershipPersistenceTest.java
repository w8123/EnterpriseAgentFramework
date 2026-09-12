package com.enterprise.ai.service.impl;

import com.enterprise.ai.domain.entity.*;
import com.enterprise.ai.embedding.EmbeddingService;
import com.enterprise.ai.pipeline.PipelineContext;
import com.enterprise.ai.pipeline.PipelineException;
import com.enterprise.ai.pipeline.document.artifact.DocumentArtifactStore;
import com.enterprise.ai.pipeline.step.MetadataPersistStep;
import com.enterprise.ai.repository.*;
import com.enterprise.ai.retrieval.KnowledgeRetrievalEngine;
import com.enterprise.ai.support.KnowledgeQueryTestDatabase;
import com.enterprise.ai.vector.VectorService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class KnowledgeFileOwnershipPersistenceTest {
    private KnowledgeQueryTestDatabase db;
    private FileInfoRepository files;
    private ChunkRepository chunks;
    private KnowledgeServiceImpl service;
    private MetadataPersistStep persist;
    private VectorService vectors;
    private DocumentArtifactStore artifacts;

    @BeforeEach
    void setup() throws Exception {
        db = new KnowledgeQueryTestDatabase(List.of("knowledge_base", "knowledge_file_info", "knowledge_chunk", "knowledge_document_import_job", "knowledge_document_index_execution"),
                KnowledgeBaseRepository.class, FileInfoRepository.class, ChunkRepository.class, DocumentImportJobRepository.class, DocumentIndexExecutionRepository.class);
        var bases = db.mapper(KnowledgeBaseRepository.class);
        files = db.mapper(FileInfoRepository.class); chunks = db.mapper(ChunkRepository.class);
        db.jdbc().update("INSERT INTO knowledge_base(id,name,code,vector_collection_name) VALUES (7,'target','target','target'),(8,'other','other','other')");
        vectors = mock(VectorService.class); artifacts = mock(DocumentArtifactStore.class);
        service = transactional(KnowledgeManagementTestServices.create(files, chunks, mock(KnowledgeTagRepository.class), org.mockito.Mockito.mock(com.enterprise.ai.service.impl.KnowledgeQuestionService.class), mock(KnowledgeOperationsQuery.class), mock(KnowledgeIndexWriteService.class), new KnowledgeBaseLookup(bases), mock(KnowledgeRetrievalEngine.class), KnowledgeIndexTestSupport.deletion(db), org.mockito.Mockito.mock(com.enterprise.ai.service.impl.KnowledgeBaseLifecycleService.class), org.mockito.Mockito.mock(com.enterprise.ai.service.impl.KnowledgeTagService.class)));
        persist = transactional(new MetadataPersistStep(bases, files, chunks, new ObjectMapper(), mock(com.enterprise.ai.pipeline.document.job.DocumentImportPublicationGuard.class), org.mockito.Mockito.mock(com.enterprise.ai.service.impl.KnowledgeTagService.class), org.mockito.Mockito.mock(com.enterprise.ai.service.impl.KnowledgeQuestionService.class)));
    }

    @AfterEach
    void close() { if (db != null) db.close(); }

    @Test
    void foreignMetadataSurvivesWriteCompensationAndExplicitDeletion() {
        seed(8);
        assertThrows(PipelineException.class, () -> persist.process(context(List.of("replacement"))));
        assertThrows(IllegalArgumentException.class, () -> service.deleteByFileId("target", "file-1"));
        assertEquals(8L, files.selectOne(null).getKnowledgeBaseId());
        assertEquals("original", chunks.selectOne(null).getContent());
        verifyNoInteractions(vectors, artifacts);
    }

    @Test
    void sameOwnerAndAttemptReplacesMetadataWithOneCurrentChunkSet() {
        seed(7);
        persist.process(context(List.of("新的段落", "second")));
        persist.process(context(List.of("最终段落")));
        assertEquals(1L, files.selectCount(null));
        assertEquals(1L, chunks.selectCount(null));
        assertEquals(7L, files.selectOne(null).getKnowledgeBaseId());
        assertEquals("job-1", files.selectOne(null).getImportJobId());
        assertEquals("最终段落", chunks.selectOne(null).getContent());
    }

    @Test
    void failedChunkInsertionRollsBackBothDeletesAndNewMetadata() {
        seed(7);
        db.jdbc().execute("ALTER TABLE knowledge_chunk ADD CONSTRAINT reject_bad_content CHECK (content <> 'reject')");
        assertThrows(RuntimeException.class, () -> persist.process(context(List.of("reject"))));
        assertEquals("original.txt", files.selectOne(null).getFileName());
        assertEquals("original", chunks.selectOne(null).getContent());
        assertEquals(1L, files.selectCount(null));
        assertEquals(1L, chunks.selectCount(null));
    }

    @Test
    void deletionRemovesOnlyOwnedRowsEvenIfAnotherCollectionHasOrphanChunks() {
        seed(7);
        db.jdbc().update("INSERT INTO knowledge_chunk(file_id,knowledge_base_id,content,chunk_index) VALUES ('file-1',8,'foreign orphan',9)");
        service.deleteByFileId("target", "file-1");
        assertEquals(0L, files.selectCount(null));
        assertEquals(1L, chunks.selectCount(null));
        assertEquals(8L, chunks.selectOne(null).getKnowledgeBaseId());
        verifyNoInteractions(vectors);
    }

    private void seed(long owner) {
        db.jdbc().update("INSERT INTO knowledge_file_info(file_id,knowledge_base_id,file_name,import_job_id) VALUES ('file-1',?,'original.txt','job-1')", owner);
        db.jdbc().update("INSERT INTO knowledge_chunk(file_id,knowledge_base_id,content,chunk_index) VALUES ('file-1',?,'original',0)", owner);
    }

    @Test
    void ownershipReadWaitsForTheCurrentFileLockAndRechecksAfterOwnerChanges() {
        seed(7);
        var transactions = new TransactionTemplate(new DataSourceTransactionManager(db.jdbc().getDataSource()));
        transactions.executeWithoutResult(status -> {
            db.jdbc().queryForObject("SELECT id FROM knowledge_file_info WHERE file_id='file-1' FOR UPDATE", Long.class);
            var blocked = CompletableFuture.runAsync(() -> persist.process(context(List.of("must not replace"))));
            var failure = assertThrows(ExecutionException.class, () -> blocked.get(10, TimeUnit.SECONDS));
            assertInstanceOf(org.springframework.dao.CannotAcquireLockException.class, failure.getCause());
            db.jdbc().update("UPDATE knowledge_file_info SET knowledge_base_id=8 WHERE file_id='file-1'");
            db.jdbc().update("UPDATE knowledge_chunk SET knowledge_base_id=8 WHERE file_id='file-1'");
        });
        assertThrows(PipelineException.class, () -> persist.process(context(List.of("must not replace"))));
        assertEquals(8L, files.selectOne(null).getKnowledgeBaseId());
        assertEquals("original", chunks.selectOne(null).getContent());
    }

    private PipelineContext context(List<String> content) {
        var context = new PipelineContext(); context.setFileId("file-1"); context.setKnowledgeBaseCode("target"); context.setKnowledgeBaseId(7L); context.setVectorCollectionName("target");
        context.setImportJobId("job-1"); context.setFileName("replacement.txt"); context.setRawText("replacement");
        context.setChunks(content); context.setVectorIds(List.of()); return context;
    }

    @SuppressWarnings("unchecked")
    private <T> T transactional(T target) {
        var proxy = new ProxyFactory(target); proxy.setProxyTargetClass(true);
        proxy.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(db.jdbc().getDataSource()),
                new AnnotationTransactionAttributeSource()));
        return (T) proxy.getProxy();
    }
}
