package com.enterprise.ai.service.impl;

import com.enterprise.ai.pipeline.PipelineContext;
import com.enterprise.ai.pipeline.PipelineException;
import com.enterprise.ai.pipeline.document.job.*;
import com.enterprise.ai.pipeline.step.VectorStoreStep;
import com.enterprise.ai.repository.*;
import com.enterprise.ai.support.KnowledgeQueryTestDatabase;
import com.enterprise.ai.vector.VectorService;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DocumentIndexExecutionTest {
    @Test
    void slowDeleteCanCheckpointAfterExpiryUnlessAnotherOwnerHasTakenOver() throws Exception {
        try (var db = database()) {
            var context = context(3); var manifest = manifest(context);
            store(db).register(context, manifest); store(db).acknowledge("lease"); expireJob(db);
            var remote = new HashSet<>(manifest.batch(0, 10));
            var vectors = mock(VectorService.class);
            doAnswer(call -> {
                remote.remove(call.getArgument(1));
                db.jdbc().update("UPDATE knowledge_document_index_execution SET cleanup_lease_until='2000-01-01 00:00:00'");
                return null;
            }).when(vectors).deleteById(anyString(), anyString());
            reclaimer(db, vectors).reclaimPending();
            assertTrue(remote.isEmpty());
            assertEquals("RECLAIMED", executions(db).selectById("lease").getState());
        }
    }

    @Test
    void concurrentRegistrationGrantsExactlyOneWritePermit() throws Exception {
        try (var db = database()) {
            var executor = java.util.concurrent.Executors.newFixedThreadPool(2);
            try {
                var start = new java.util.concurrent.CountDownLatch(1);
                java.util.concurrent.Callable<Boolean> register = () -> { start.await(); var c = context(1); return store(db).register(c, manifest(c)); };
                var a = executor.submit(register); var b = executor.submit(register); start.countDown();
                assertNotEquals(a.get(10, java.util.concurrent.TimeUnit.SECONDS), b.get(10, java.util.concurrent.TimeUnit.SECONDS));
                assertEquals(1, db.jdbc().queryForObject("SELECT COUNT(*) FROM knowledge_document_index_execution", Integer.class));
            } finally { executor.shutdownNow(); }
        }
    }

    @Test
    void registrationCommitsBeforeExternalCallAndOnlyFirstRegistrationAllowsWriting() throws Exception {
        try (var db = database()) {
            var store = store(db);
            var context = context(2);
            var vector = mock(VectorService.class);
            doAnswer(call -> {
                assertEquals(1, db.jdbc().queryForObject("SELECT COUNT(*) FROM knowledge_document_index_execution", Integer.class));
                assertFalse(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());
                return null;
            }).when(vector).upsert(anyString(), anyList(), anyList(), anyList(), anyList());
            var step = new VectorStoreStep(vector, store);
            step.process(context);
            assertTrue(executions(db).selectById("lease").getWriteAcknowledged());
            assertFalse(store.register(context, manifest(context)));
            assertThrows(PipelineException.class, () -> store.register(context, new DocumentIndexVectorManifest("a".repeat(64), 2)));
            assertThrows(PipelineException.class, () -> store.register(context, DocumentIndexVectorManifest.forExecution("file", "lease", 3)));
            assertThrows(PipelineException.class, () -> step.process(context));
            verify(vector, times(1)).upsert(anyString(), anyList(), anyList(), anyList(), anyList());
        }
    }

    @Test
    void expiredRegistrationNeverCallsVectorStorage() throws Exception {
        try (var db = database()) {
            expireJob(db);
            var vectors = mock(VectorService.class);
            assertThrows(PipelineException.class, () -> new VectorStoreStep(vectors, store(db)).process(context(1)));
            verifyNoInteractions(vectors);
            assertEquals(0, db.jdbc().queryForObject("SELECT COUNT(*) FROM knowledge_document_index_execution", Integer.class));
        }
    }

    @Test
    void unknownWriteIsResweptAfterLateDataArrivesAndAfterStoreRestart() throws Exception {
        try (var db = database()) {
            var context = context(2); var manifest = manifest(context);
            store(db).register(context, manifest);
            expireJob(db);
            var remote = new HashSet<String>();
            var vectors = deleting(remote);
            reclaimer(db, vectors).reclaimPending();
            assertEquals("RECLAIMING", executions(db).selectById("lease").getState());
            assertEquals(0, executions(db).selectById("lease").getCleanupCursor());
            remote.addAll(manifest.batch(0, 10)); // Original remote write completes after first empty sweep.
            makeDue(db);
            reclaimer(db, vectors).reclaimPending(); // New store/reclaimer simulates loss of all worker memory.
            assertTrue(remote.isEmpty());
            assertEquals("RECLAIMING", executions(db).selectById("lease").getState());
        }
    }

    @Test
    void lateAcknowledgementInvalidatesInFlightSweepAndRestartsFromZero() throws Exception {
        try (var db = database()) {
            var context = context(102); var manifest = manifest(context);
            store(db).register(context, manifest); expireJob(db);
            var remote = new HashSet<>(manifest.batch(0, 1000));
            reclaimer(db, deleting(remote)).reclaimPending();
            assertEquals(100, executions(db).selectById("lease").getCleanupCursor());
            var vectors = deleting(remote);
            doAnswer(call -> {
                remote.addAll(manifest.batch(0, 1000)); // Full late write, including previously deleted prefix.
                store(db).acknowledge("lease");
                remote.remove(call.getArgument(1));
                return null;
            }).when(vectors).deleteById("kb", manifest.vectorId(100));
            reclaimer(db, vectors).reclaimPending();
            assertEquals(0, executions(db).selectById("lease").getCleanupCursor());
            assertEquals("RECLAIMING", executions(db).selectById("lease").getState());
            assertTrue(remote.contains(manifest.vectorId(0)));
            reclaimer(db, deleting(remote)).reclaimPending();
            reclaimer(db, deleting(remote)).reclaimPending();
            assertTrue(remote.isEmpty());
            assertEquals("RECLAIMED", executions(db).selectById("lease").getState());
        }
    }

    @Test
    void partialFailurePersistsCursorAndResumesExactIds() throws Exception {
        try (var db = database()) {
            var context = context(3); var manifest = manifest(context);
            store(db).register(context, manifest); store(db).acknowledge("lease"); expireJob(db);
            var remote = new HashSet<>(manifest.batch(0, 10));
            var vectors = deleting(remote);
            doThrow(new IllegalStateException("sensitive remote text")).when(vectors).deleteById("kb", manifest.vectorId(1));
            reclaimer(db, vectors).reclaimPending();
            var execution = executions(db).selectById("lease");
            assertEquals(1, execution.getCleanupCursor());
            assertEquals("IllegalStateException", execution.getLastCleanupError());
            makeDue(db); var recovered = deleting(remote);
            reclaimer(db, recovered).reclaimPending();
            verify(recovered, never()).deleteById("kb", manifest.vectorId(0));
            assertTrue(remote.isEmpty());
            assertEquals("RECLAIMED", executions(db).selectById("lease").getState());
        }
    }

    @Test
    void currentJobPublishedExecutionAndCompetingCleanupLeasesAreFenced() throws Exception {
        try (var db = database()) {
            var store = store(db); var repo = executions(db); var context = context(1);
            store.register(context, manifest(context)); store.acknowledge("lease");
            assertTrue(repo.findReclaimable(8).isEmpty());
            assertNull(store.claim(repo.selectById("lease"), 60));
            expireJob(db);
            var first = store.claim(repo.selectById("lease"), 60);
            assertNotNull(first); assertNull(store.claim(first, 60));
            db.jdbc().update("UPDATE knowledge_document_index_execution SET cleanup_lease_until='2000-01-01 00:00:00'");
            var second = store.claim(first, 60);
            assertNotEquals(first.getCleanupLeaseOwner(), second.getCleanupLeaseOwner());
            assertEquals(0, repo.progress("lease", first.getCleanupLeaseOwner(), 0, 1, "RECLAIMED", 0, null));
            assertEquals(1, repo.progress("lease", second.getCleanupLeaseOwner(), 0, 1, "RECLAIMED", 0, null));
            db.jdbc().update("UPDATE knowledge_document_index_execution SET state='PUBLISHED'");
            assertTrue(repo.findReclaimable(8).isEmpty());
            assertNull(store.claim(repo.selectById("lease"), 60));
        }
    }

    private static KnowledgeQueryTestDatabase database() throws Exception {
        var db = new KnowledgeQueryTestDatabase(List.of("knowledge_document_import_job", "knowledge_document_index_execution", "knowledge_base", "knowledge_file_info", "knowledge_chunk"),
                DocumentImportJobRepository.class, DocumentIndexExecutionRepository.class, KnowledgeBaseRepository.class, FileInfoRepository.class, ChunkRepository.class);
        db.jdbc().update("INSERT INTO knowledge_base(id,name,code,vector_collection_name) VALUES (7,'target','kb','kb')");
        db.jdbc().update("INSERT INTO knowledge_document_import_job(job_id,file_id,knowledge_base_id,knowledge_base_code,file_name,file_type,source_object_key,provider_type,status,stage,lease_owner,lease_until,vector_collection_name) VALUES ('job','file',7,'kb','a.pdf','pdf','source','DOCLING','INDEXING','INDEXING','lease','2099-01-01 00:00:00','kb')");
        return db;
    }

    private static DocumentIndexExecutionRepository executions(KnowledgeQueryTestDatabase db) { return db.mapper(DocumentIndexExecutionRepository.class); }
    private static DocumentIndexExecutionStore store(KnowledgeQueryTestDatabase db) {
        return KnowledgeIndexTestSupport.executions(db);
    }
    private static DocumentIndexReclaimer reclaimer(KnowledgeQueryTestDatabase db, VectorService vectors) {
        return new DocumentIndexReclaimer(executions(db), store(db), vectors, new DocumentImportJobProperties());
    }
    private static PipelineContext context(int count) {
        var c = new PipelineContext(); c.setImportJobId("job"); c.setFileId("file"); c.setImportLeaseOwner("lease"); c.setKnowledgeBaseCode("kb"); c.setKnowledgeBaseId(7L); c.setVectorCollectionName("kb");
        c.setChunks(Collections.nCopies(count, "正文")); c.setVectors(Collections.nCopies(count, List.of(1.0f, 0.0f)));
        return c;
    }
    private static DocumentIndexVectorManifest manifest(PipelineContext c) { return DocumentIndexVectorManifest.forExecution(c.getFileId(), c.getImportLeaseOwner(), c.getVectors().size()); }
    private static void expireJob(KnowledgeQueryTestDatabase db) { db.jdbc().update("UPDATE knowledge_document_import_job SET status='FAILED',lease_owner=NULL,lease_until=NULL"); }
    private static void makeDue(KnowledgeQueryTestDatabase db) { db.jdbc().update("UPDATE knowledge_document_index_execution SET next_cleanup_at='2000-01-01 00:00:00'"); }
    private static VectorService deleting(Set<String> remote) {
        var vectors = mock(VectorService.class);
        doAnswer(call -> { remote.remove(call.getArgument(1)); return null; }).when(vectors).deleteById(anyString(), anyString());
        return vectors;
    }
}
