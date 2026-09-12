package com.enterprise.ai.service.impl;

import com.enterprise.ai.pipeline.*;
import com.enterprise.ai.pipeline.step.MetadataPersistStep;
import com.enterprise.ai.pipeline.document.job.DocumentImportPublicationGuard;
import com.enterprise.ai.pipeline.document.job.DocumentIndexExecutionStore;
import com.enterprise.ai.pipeline.document.job.DocumentIndexVectorManifest;
import com.enterprise.ai.repository.*;
import com.enterprise.ai.support.KnowledgeQueryTestDatabase;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class DocumentImportPublicationTest {
    @ParameterizedTest
    @ValueSource(strings = {"current", "stale", "expired", "foreign", "missing"})
    void onlyCurrentExecutionCanPersistMetadata(String mode) throws Exception {
        try (var db = new KnowledgeQueryTestDatabase(List.of("knowledge_base", "knowledge_file_info", "knowledge_chunk", "knowledge_document_import_job", "knowledge_document_index_execution"),
                KnowledgeBaseRepository.class, FileInfoRepository.class, ChunkRepository.class, DocumentImportJobRepository.class, DocumentIndexExecutionRepository.class)) {
            db.jdbc().update("INSERT INTO knowledge_base(id,name,code,vector_collection_name) VALUES (7,'target','kb','kb')");
            String lease = mode.equals("stale") ? "stale" : "current";
            db.jdbc().update("INSERT INTO knowledge_document_import_job(job_id,file_id,knowledge_base_id,knowledge_base_code,file_name,file_type,source_object_key,provider_type,status,stage,lease_owner,lease_until,vector_collection_name) VALUES ('job','file',?,'kb','a.pdf','pdf','source','DOCLING','INDEXING','INDEXING',?,'2099-01-01 00:00:00','kb')",
                    mode.equals("foreign") ? 8 : 7, lease);
            var jobs = db.mapper(DocumentImportJobRepository.class);
            var transactionManager = new DataSourceTransactionManager(db.jdbc().getDataSource());
            var executions = KnowledgeIndexTestSupport.executions(db);
            var step = new MetadataPersistStep(db.mapper(KnowledgeBaseRepository.class), db.mapper(FileInfoRepository.class),
                    db.mapper(ChunkRepository.class), new ObjectMapper(), new DocumentImportPublicationGuard(jobs, executions, org.mockito.Mockito.mock(com.enterprise.ai.service.impl.KnowledgeFileDeletionService.class)), org.mockito.Mockito.mock(com.enterprise.ai.service.impl.KnowledgeTagService.class), org.mockito.Mockito.mock(com.enterprise.ai.service.impl.KnowledgeQuestionService.class));
            var context = new PipelineContext();
            context.setFileId("file"); context.setFileName("a.pdf"); context.setKnowledgeBaseCode("kb"); context.setKnowledgeBaseId(7L); context.setVectorCollectionName("kb");
            context.setImportJobId("job"); context.setImportLeaseOwner(lease);
            context.setChunks(List.of("published text"));
            var manifest = DocumentIndexVectorManifest.forExecution("file", lease, 1);
            context.setVectorIds(manifest.batch(0, 1));
            if (mode.equals("foreign")) {
                db.jdbc().update("INSERT INTO knowledge_base(id,name,code,vector_collection_name) VALUES (8,'foreign','foreign','foreign')");
                db.jdbc().update("UPDATE knowledge_document_import_job SET knowledge_base_code='foreign',vector_collection_name='foreign' WHERE job_id='job'");
                context.setKnowledgeBaseId(8L); context.setKnowledgeBaseCode("foreign"); context.setVectorCollectionName("foreign");
            }
            assertTrue(executions.register(context, manifest));
            executions.acknowledge(lease);
            if (mode.equals("foreign")) {
                context.setKnowledgeBaseId(7L); context.setKnowledgeBaseCode("kb"); context.setVectorCollectionName("kb");
            }
            if (mode.equals("stale")) db.jdbc().update("UPDATE knowledge_document_import_job SET lease_owner='current'");
            if (mode.equals("expired")) db.jdbc().update("UPDATE knowledge_document_import_job SET lease_until='2000-01-01 00:00:00'");
            if (mode.equals("missing")) context.setImportLeaseOwner(null);
            var tx = new TransactionTemplate(transactionManager);
            if (mode.equals("current")) {
                tx.executeWithoutResult(status -> {
                    step.process(context);
                    status.setRollbackOnly();
                });
                assertEquals(0, db.jdbc().queryForObject("SELECT COUNT(*) FROM knowledge_chunk", Integer.class));
                assertEquals(0, db.jdbc().queryForObject("SELECT COUNT(*) FROM knowledge_file_info", Integer.class));
                assertFalse(context.isImportPublished());
                assertEquals("INDEXING", db.jdbc().queryForObject("SELECT status FROM knowledge_document_import_job", String.class));
                assertEquals("REGISTERED", db.jdbc().queryForObject("SELECT state FROM knowledge_document_index_execution", String.class));
                assertEquals(1, db.jdbc().queryForObject("SELECT write_acknowledged FROM knowledge_document_index_execution", Integer.class));
                db.jdbc().execute("SET DEFAULT_LOCK_TIMEOUT 200");
                var executor = java.util.concurrent.Executors.newSingleThreadExecutor();
                try {
                    tx.executeWithoutResult(status -> {
                        step.process(context);
                        var competingUpdate = executor.submit(() -> db.jdbc().update(
                                "UPDATE knowledge_document_import_job SET status='FAILED', lease_owner=NULL WHERE job_id='job'"));
                        var failure = assertThrows(java.util.concurrent.ExecutionException.class,
                                () -> competingUpdate.get(5, java.util.concurrent.TimeUnit.SECONDS));
                        var timeout = assertInstanceOf(org.springframework.dao.QueryTimeoutException.class, failure.getCause());
                        assertEquals(50200, assertInstanceOf(java.sql.SQLException.class, timeout.getCause()).getErrorCode());
                    });
                    assertTrue(context.isImportPublished());
                    assertEquals("COMPLETED", db.jdbc().queryForObject("SELECT status FROM knowledge_document_import_job", String.class));
                    assertEquals("PUBLISHED", db.jdbc().queryForObject("SELECT state FROM knowledge_document_index_execution", String.class));
                    assertEquals(0, executor.submit(() -> jobs.failIndexing("job", lease, "late failure"))
                            .get(5, java.util.concurrent.TimeUnit.SECONDS));
                    assertEquals("COMPLETED", db.jdbc().queryForObject("SELECT status FROM knowledge_document_import_job", String.class));
                } finally {
                    executor.shutdownNow();
                }
                assertEquals(manifest.vectorId(0), db.jdbc().queryForObject("SELECT vector_id FROM knowledge_chunk", String.class));
            } else {
                assertThrows(PipelineException.class, () -> tx.executeWithoutResult(status -> step.process(context)));
                assertEquals(0, db.jdbc().queryForObject("SELECT COUNT(*) FROM knowledge_chunk", Integer.class));
                assertEquals(0, db.jdbc().queryForObject("SELECT COUNT(*) FROM knowledge_file_info", Integer.class));
                assertEquals("REGISTERED", db.jdbc().queryForObject("SELECT state FROM knowledge_document_index_execution", String.class));
                assertFalse(context.isImportPublished());
            }
        }
    }
}
