package com.enterprise.ai.pipeline.document.artifact;

import com.enterprise.ai.domain.dto.DocumentImportAccessContext;
import com.enterprise.ai.domain.entity.FileInfo;
import com.enterprise.ai.domain.entity.KnowledgeBase;
import com.enterprise.ai.domain.entity.DocumentArtifactLifecycle;
import com.enterprise.ai.domain.entity.DocumentImportJob;
import com.enterprise.ai.pipeline.document.DocumentFormat;
import com.enterprise.ai.pipeline.document.DocumentParseRouter;
import com.enterprise.ai.pipeline.document.job.DocumentImportJobProperties;
import com.enterprise.ai.pipeline.document.job.DocumentImportJobWorker;
import com.enterprise.ai.pipeline.document.job.DocumentIndexExecutionStore;
import com.enterprise.ai.pipeline.step.ChunkStep;
import com.enterprise.ai.pipeline.step.TextCleanStep;
import com.enterprise.ai.repository.*;
import com.enterprise.ai.service.impl.DocumentImportJobServiceImpl;
import com.enterprise.ai.service.impl.KnowledgeFileDeletionService;
import com.enterprise.ai.support.KnowledgeQueryTestDatabase;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DocumentArtifactLifecycleTest {
    private static final String KEY = "knowledge-document-import/dij_12345678901234567890123456789012/source/original";
    private KnowledgeQueryTestDatabase db;
    private DataSourceTransactionManager manager;
    private DocumentArtifactBackend raw;
    private DocumentArtifactStore store;
    private final Map<String, byte[]> objects = new HashMap<>();

    @BeforeEach
    void setUp() throws Exception {
        db = new KnowledgeQueryTestDatabase(List.of("knowledge_base", "knowledge_file_info", "knowledge_chunk",
                "knowledge_document_import_job", "knowledge_document_index_execution", "knowledge_user_file_permission",
                "knowledge_question", "knowledge_document_artifact_lifecycle"),
                KnowledgeBaseRepository.class, FileInfoRepository.class, ChunkRepository.class,
                DocumentImportJobRepository.class, DocumentIndexExecutionRepository.class,
                UserFilePermissionRepository.class, KnowledgeQuestionRepository.class, DocumentArtifactLifecycleRepository.class);
        manager = new DataSourceTransactionManager(db.jdbc().getDataSource());
        var kb = new KnowledgeBase();
        kb.setId(7L); kb.setCode("kb"); kb.setName("工件生命周期验证");
        kb.setVectorCollectionName("reachai_kb_12345678901234567890123456789012");
        kb.setWorkspaceId("workspace"); kb.setScope("WORKSPACE");
        db.mapper(KnowledgeBaseRepository.class).insert(kb);
        raw = mock(DocumentArtifactBackend.class);
        when(raw.storageId()).thenReturn("a".repeat(64));
        doAnswer(call -> { objects.remove(call.getArgument(0, String.class)); return null; }).when(raw).delete(anyString());
        doAnswer(call -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive(), "Backend writes must run outside metadata transactions");
            objects.put(call.getArgument(0), call.getArgument(1, InputStream.class).readAllBytes());
            return null;
        }).when(raw).put(anyString(), any(), anyLong(), anyString());
        when(raw.open(anyString())).thenAnswer(call -> new ByteArrayInputStream(objects.get(call.getArgument(0, String.class))));
        store = new ManagedDocumentArtifactStore(raw, journal(), manager);
    }

    @AfterEach
    void close() { db.close(); }

    @Test
    void springSelectsOneManagedStoreAndInitializesTheLocalBackend(@org.junit.jupiter.api.io.TempDir java.nio.file.Path root) {
        try (var context = new org.springframework.context.annotation.AnnotationConfigApplicationContext()) {
            var properties = new DocumentArtifactProperties(); properties.setLocalRoot(root.toString());
            context.registerBean(DocumentArtifactProperties.class, () -> properties);
            context.registerBean(org.springframework.transaction.PlatformTransactionManager.class, () -> manager);
            context.registerBean(DocumentArtifactLifecycleRepository.class, () -> db.mapper(DocumentArtifactLifecycleRepository.class));
            context.registerBean(DocumentImportJobRepository.class, () -> db.mapper(DocumentImportJobRepository.class));
            context.registerBean(FileInfoRepository.class, () -> db.mapper(FileInfoRepository.class));
            context.register(LocalDocumentArtifactStore.class, MinioDocumentArtifactStore.class,
                    ManagedDocumentArtifactStore.class, DocumentArtifactLifecycleStore.class, DocumentArtifactReclaimer.class);
            context.refresh();
            assertEquals(1, context.getBeansOfType(DocumentArtifactStore.class).size());
            assertEquals(1, context.getBeansOfType(DocumentArtifactBackend.class).size());
            assertInstanceOf(LocalDocumentArtifactStore.class, context.getBean(DocumentArtifactBackend.class));
            var managed = context.getBean(DocumentArtifactStore.class);
            byte[] bytes = "容器接线验证".getBytes(StandardCharsets.UTF_8);
            managed.put(KEY, new ByteArrayInputStream(bytes), bytes.length, "text/plain");
            assertTrue(java.nio.file.Files.exists(root.resolve(KEY)));
            managed.retire(KEY);
            context.getBean(DocumentArtifactReclaimer.class).reclaimPending();
            assertFalse(java.nio.file.Files.exists(root.resolve(KEY)));
            assertEquals("RECLAIMED", db.jdbc().queryForObject(
                    "SELECT state FROM knowledge_document_artifact_lifecycle WHERE object_key=?", String.class, KEY));
        }
    }

    @Test
    void failedDeletionRemainsDiscoverableAfterServicesAreRecreated() {
        file("first", KEY);
        doThrow(new IllegalStateException("controlled object-store failure")).when(raw).delete(KEY);

        deletion().deleteByFileId("kb", "first");
        reclaimer().reclaimPending();

        assertEquals(0, db.jdbc().queryForObject("SELECT COUNT(*) FROM knowledge_file_info", Integer.class));
        assertEquals(1, db.jdbc().queryForObject(
                "SELECT COUNT(*) FROM knowledge_document_artifact_lifecycle WHERE object_key=?", Integer.class, KEY),
                "A committed file deletion must retain durable artifact cleanup intent");
        assertEquals("IllegalStateException", saved(KEY).getLastCleanupError());
        doAnswer(call -> { objects.remove(KEY); return null; }).when(raw).delete(KEY);
        due(KEY);
        reclaimer().reclaimPending();
        verify(raw, times(2)).delete(KEY);
        assertEquals("RECLAIMING", saved(KEY).getState(), "Legacy write completion must remain unknown");
    }

    @Test
    void fileDeletionCannotRemoveArtifactSharedByAnotherPublishedFile() {
        file("first", KEY);
        file("second", KEY);
        objects.put(KEY, "保留的原件".getBytes(StandardCharsets.UTF_8));

        deletion().deleteByFileId("kb", "first");
        reclaimer().reclaimPending();

        assertEquals(1, db.jdbc().queryForObject("SELECT COUNT(*) FROM knowledge_file_info", Integer.class));
        assertTrue(objects.containsKey(KEY), "Cleanup must preserve artifacts still referenced by another file");
        verify(raw, never()).delete(KEY);
        deletion().deleteByFileId("kb", "second");
        due(KEY);
        reclaimer().reclaimPending();
        assertFalse(objects.containsKey(KEY));
    }

    @Test
    void sourceUploadRegistersItsIdentityBeforeTheObjectStoreSeesBytes() {
        doAnswer(call -> {
            String key = call.getArgument(0);
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            assertEquals(1, db.jdbc().queryForObject(
                    "SELECT COUNT(*) FROM knowledge_document_artifact_lifecycle WHERE object_key=?", Integer.class, key),
                    "A source write must have a committed identity before object-store IO");
            return null;
        }).when(raw).put(anyString(), any(), anyLong(), anyString());
        var router = mock(DocumentParseRouter.class);
        when(router.detect(any())).thenReturn(DocumentFormat.TXT);
        var properties = new DocumentImportJobProperties();
        properties.setEnabled(false);
        var service = new DocumentImportJobServiceImpl(db.mapper(DocumentImportJobRepository.class),
                db.mapper(KnowledgeBaseRepository.class), db.mapper(FileInfoRepository.class), store, router,
                new ObjectMapper(), properties, mock(DocumentImportJobWorker.class),
                mock(TextCleanStep.class), mock(ChunkStep.class), Runnable::run, manager);
        var upload = new MockMultipartFile("file", "原件.txt", "text/plain", "原件内容".getBytes(StandardCharsets.UTF_8));

        new TransactionTemplate(manager).executeWithoutResult(status -> {
            service.submit(upload, "kb", "fixed_length", 500, 50, Map.of(), false,
                    new DocumentImportAccessContext("tenant", "actor", "workspace", null, "WORKSPACE"));
            status.setRollbackOnly();
        });
        assertEquals(1, db.jdbc().queryForObject("SELECT COUNT(*) FROM knowledge_document_artifact_lifecycle WHERE state='RETAINED'", Integer.class));
        assertEquals(1, db.jdbc().queryForObject("SELECT COUNT(*) FROM knowledge_document_import_job", Integer.class),
                "Submission owns its commit independently of an outer caller transaction");
    }

    @Test
    void aRolledBackDeletionPreservesReferencesAndTheRetainedObject() {
        write(KEY);
        new TransactionTemplate(manager).executeWithoutResult(status -> {
            store.retain(KEY);
            file("first", KEY);
        });
        new TransactionTemplate(manager).executeWithoutResult(status -> {
            deletion().deleteByFileId("kb", "first");
            status.setRollbackOnly();
        });
        reclaimer().reclaimPending();
        assertEquals("RETAINED", saved(KEY).getState());
        assertEquals(1, db.jdbc().queryForObject("SELECT COUNT(*) FROM knowledge_file_info", Integer.class));
        assertEquals("原件内容", new String(objects.get(KEY), StandardCharsets.UTF_8));
        verify(raw, never()).delete(KEY);
    }

    @Test
    void failingToRecordRetirementRollsBackTheBusinessDeletion() {
        write(KEY);
        new TransactionTemplate(manager).executeWithoutResult(status -> {
            store.retain(KEY);
            file("first", KEY);
        });
        db.jdbc().execute("ALTER TABLE knowledge_document_artifact_lifecycle ADD CONSTRAINT refuse_retirement CHECK (state <> 'RECLAIMING')");
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class,
                () -> deletion().deleteByFileId("kb", "first"));
        assertEquals(1, db.jdbc().queryForObject("SELECT COUNT(*) FROM knowledge_file_info", Integer.class));
        assertEquals("RETAINED", saved(KEY).getState());
        assertTrue(objects.containsKey(KEY));
        verify(raw, never()).delete(KEY);
    }

    @Test
    void publicationRollbackLeavesTheWriteDiscoverableWithoutAReference() {
        write(KEY);
        new TransactionTemplate(manager).executeWithoutResult(status -> {
            store.retain(KEY);
            file("first", KEY);
            status.setRollbackOnly();
        });
        assertEquals("WRITING", saved(KEY).getState());
        assertEquals(0, db.jdbc().queryForObject("SELECT COUNT(*) FROM knowledge_file_info", Integer.class));
        expire(KEY);
        reclaimer().reclaimPending();
        assertEquals("RECLAIMED", saved(KEY).getState());
        assertFalse(objects.containsKey(KEY));
    }

    @Test
    void anUnknownWriteIsRevisitedAfterItsLateObjectAppears() {
        doAnswer(call -> {
            objects.put(KEY, "第一次写入".getBytes(StandardCharsets.UTF_8));
            throw new DocumentArtifactException("controlled lost result");
        }).when(raw).put(eq(KEY), any(), anyLong(), anyString());
        assertThrows(DocumentArtifactException.class, () -> write(KEY));
        assertFalse(saved(KEY).getWriteAcknowledged());
        reclaimer().reclaimPending();
        assertFalse(objects.containsKey(KEY));
        assertEquals("RECLAIMING", saved(KEY).getState());

        objects.put(KEY, "受控迟到的写入".getBytes(StandardCharsets.UTF_8));
        due(KEY);
        reclaimer().reclaimPending();
        assertFalse(objects.containsKey(KEY));
        assertEquals("RECLAIMING", saved(KEY).getState());
        verify(raw, times(2)).delete(KEY);
    }

    @Test
    void aLateWriteConfirmationRevokesTheOldCleanupLease() {
        journal().register(raw.storageId(), KEY);
        store.retire(KEY);
        var oldClaim = journal().claim(raw.storageId(), saved(KEY));
        assertNotNull(oldClaim);
        raw.delete(KEY);
        objects.put(KEY, "迟到后已确认".getBytes(StandardCharsets.UTF_8));
        journal().acknowledge(raw.storageId(), KEY);
        journal().finish(oldClaim, null);
        assertEquals("RECLAIMING", saved(KEY).getState());
        assertNull(saved(KEY).getCleanupLeaseOwner());
        assertTrue(objects.containsKey(KEY));
        assertThrows(IllegalStateException.class,
                () -> new TransactionTemplate(manager).executeWithoutResult(status -> store.retain(KEY)));
        reclaimer().reclaimPending();
        assertEquals("RECLAIMED", saved(KEY).getState());
        assertFalse(objects.containsKey(KEY));
    }

    @Test
    void retirementDoesNotPermitReusingTheSamePhysicalWriteIdentity() {
        write(KEY);
        store.retire(KEY);
        reclaimer().reclaimPending();
        assertEquals("RECLAIMED", saved(KEY).getState());
        assertThrows(org.springframework.dao.DuplicateKeyException.class, () -> write(KEY));
        verify(raw, times(1)).put(eq(KEY), any(), anyLong(), anyString());
        assertFalse(objects.containsKey(KEY));
    }

    @Test
    void aDifferentStorageBackendCannotReclaimThisBackendsObjects() {
        write(KEY);
        expire(KEY);
        var other = mock(DocumentArtifactBackend.class);
        when(other.storageId()).thenReturn("b".repeat(64));
        new DocumentArtifactReclaimer(other, db.mapper(DocumentArtifactLifecycleRepository.class), journal()).reclaimPending();
        assertNull(journal().claim(other.storageId(), saved(KEY)));
        verify(other, never()).delete(anyString());
        assertTrue(objects.containsKey(KEY));
        reclaimer().reclaimPending();
        assertEquals("RECLAIMED", saved(KEY).getState());
    }

    @Test
    void aRetryableJobReferenceProtectsItsSourceUntilCancellation() {
        write(KEY);
        new TransactionTemplate(manager).executeWithoutResult(status -> {
            store.retain(KEY);
            var job = new DocumentImportJob();
            job.setJobId("retained_job"); job.setFileId("future_file"); job.setKnowledgeBaseId(7L);
            job.setKnowledgeBaseCode("kb"); job.setFileName("来源.txt"); job.setFileType("txt");
            job.setSourceObjectKey(KEY); job.setProviderType("JAVA_FAST"); job.setStatus("FAILED"); job.setStage("PARSING");
            db.mapper(DocumentImportJobRepository.class).insert(job);
        });
        store.retire(KEY);
        reclaimer().reclaimPending();
        assertTrue(objects.containsKey(KEY));
        verify(raw, never()).delete(KEY);
        db.jdbc().update("UPDATE knowledge_document_import_job SET status='CANCELLED' WHERE job_id='retained_job'");
        due(KEY);
        reclaimer().reclaimPending();
        assertEquals("RECLAIMED", saved(KEY).getState());
        assertFalse(objects.containsKey(KEY));
    }

    @Test
    void anExpiredUnreferencedWriteCannotBePublished() {
        write(KEY);
        expire(KEY);
        assertThrows(IllegalStateException.class,
                () -> new TransactionTemplate(manager).executeWithoutResult(status -> store.retain(KEY)));
        assertEquals("WRITING", saved(KEY).getState());
        reclaimer().reclaimPending();
        assertEquals("RECLAIMED", saved(KEY).getState());
    }

    @Test
    void pathAliasesAreRejectedBeforeRecordingOrWritingAnything() {
        assertThrows(IllegalArgumentException.class, () -> write("knowledge-document-import/job/../source"));
        assertThrows(IllegalArgumentException.class, () -> write("knowledge-document-import//source"));
        assertThrows(IllegalArgumentException.class, () -> write(".reachai-storage-id"));
        assertEquals(0, db.jdbc().queryForObject("SELECT COUNT(*) FROM knowledge_document_artifact_lifecycle", Integer.class));
        verify(raw, never()).put(anyString(), any(), anyLong(), anyString());
    }

    private void write(String key) {
        byte[] content = "原件内容".getBytes(StandardCharsets.UTF_8);
        store.put(key, new ByteArrayInputStream(content), content.length, "text/plain");
    }

    private DocumentArtifactLifecycle saved(String key) {
        return db.mapper(DocumentArtifactLifecycleRepository.class)
                .selectById(DocumentArtifactIdentity.artifact(raw.storageId(), key));
    }

    private void due(String key) {
        db.jdbc().update("UPDATE knowledge_document_artifact_lifecycle SET next_cleanup_at=TIMESTAMPADD(SECOND,-1,CURRENT_TIMESTAMP) WHERE object_key=?", key);
    }

    private void expire(String key) {
        db.jdbc().update("UPDATE knowledge_document_artifact_lifecycle SET publication_deadline=TIMESTAMPADD(SECOND,-1,CURRENT_TIMESTAMP) WHERE object_key=?", key);
    }

    private DocumentArtifactReclaimer reclaimer() {
        return new DocumentArtifactReclaimer(raw, db.mapper(DocumentArtifactLifecycleRepository.class), journal());
    }

    private void file(String fileId, String key) {
        var file = new FileInfo();
        file.setFileId(fileId); file.setKnowledgeBaseId(7L); file.setFileName("验证.txt");
        file.setSourceObjectKey(key); file.setStatus(1);
        db.mapper(FileInfoRepository.class).insert(file);
    }

    private KnowledgeFileDeletionService deletion() {
        return new KnowledgeFileDeletionService(db.mapper(KnowledgeBaseRepository.class), db.mapper(FileInfoRepository.class),
                db.mapper(ChunkRepository.class), db.mapper(DocumentImportJobRepository.class),
                db.mapper(DocumentIndexExecutionRepository.class), mock(DocumentIndexExecutionStore.class), store,
                db.mapper(UserFilePermissionRepository.class), new com.enterprise.ai.service.impl.KnowledgeQuestionService(db.mapper(KnowledgeBaseRepository.class), db.mapper(ChunkRepository.class), db.mapper(KnowledgeQuestionRepository.class), manager), manager, org.mockito.Mockito.mock(com.enterprise.ai.service.impl.KnowledgeTagService.class));
    }

    private DocumentArtifactLifecycleStore journal() {
        return new DocumentArtifactLifecycleStore(db.mapper(DocumentArtifactLifecycleRepository.class),
                db.mapper(DocumentImportJobRepository.class), db.mapper(FileInfoRepository.class), manager);
    }
}
