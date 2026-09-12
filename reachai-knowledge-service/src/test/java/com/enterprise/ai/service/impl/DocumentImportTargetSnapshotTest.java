package com.enterprise.ai.service.impl;

import com.enterprise.ai.pipeline.*;
import com.enterprise.ai.pipeline.document.job.*;
import com.enterprise.ai.pipeline.step.VectorStoreStep;
import com.enterprise.ai.pipeline.step.MetadataPersistStep;
import com.enterprise.ai.pipeline.step.TextCleanStep;
import com.enterprise.ai.pipeline.step.ChunkStep;
import com.enterprise.ai.pipeline.document.DocumentFormat;
import com.enterprise.ai.pipeline.document.DocumentParseRouter;
import com.enterprise.ai.pipeline.document.artifact.DocumentArtifactStore;
import com.enterprise.ai.domain.dto.DocumentImportAccessContext;
import com.enterprise.ai.repository.*;
import com.enterprise.ai.support.KnowledgeQueryTestDatabase;
import com.enterprise.ai.vector.VectorService;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DocumentImportTargetSnapshotTest {
    private KnowledgeQueryTestDatabase db;

    @BeforeEach
    void setup() throws Exception {
        db = new KnowledgeQueryTestDatabase(List.of("knowledge_base", "knowledge_document_import_job", "knowledge_document_index_execution", "knowledge_file_info", "knowledge_chunk"),
                KnowledgeBaseRepository.class, DocumentImportJobRepository.class, DocumentIndexExecutionRepository.class,
                FileInfoRepository.class, ChunkRepository.class);
        seedKnowledgeBase("physical-old");
    }

    @AfterEach
    void close() { if (db != null) db.close(); }

    @Test
    void reusedDatabaseIdAndBusinessCodeCannotReplaceAnAlreadyBoundPhysicalTarget() {
        var context = context("physical-old");
        db.jdbc().update("DELETE FROM knowledge_base WHERE id=7");
        seedKnowledgeBase("physical-new");
        var factory = mock(PipelineFactory.class);
        var pipeline = new KnowledgeImportPipeline("kb");
        var executed = new ArrayList<String>();
        pipeline.addStep(new PipelineStep() {
            public String getName() { return "METADATA_PERSIST"; }
            public void process(PipelineContext c) { executed.add(c.getVectorCollectionName()); }
        });
        when(factory.create("kb")).thenReturn(pipeline);
        var result = new PipelineImportServiceImpl(factory, new KnowledgeBaseLookup(db.mapper(KnowledgeBaseRepository.class)), mock(com.enterprise.ai.pipeline.document.job.DocumentIndexExecutionStore.class)).execute(context);
        assertEquals("FAILED", result.getStatus(), "An old physical target must not be replaced by a new object with the same database ID");
        assertEquals("physical-old", context.getVectorCollectionName());
        assertTrue(executed.isEmpty());
    }

    @Test
    void aJobWithoutAPersistedPhysicalSnapshotCannotGrantAWritePermit() {
        seedJob("INDEXING");
        var vectors = mock(VectorService.class);
        assertThrows(PipelineException.class, () -> new VectorStoreStep(vectors, store()).process(context("physical-old")),
                "Missing persisted physical identity must fail before external writes");
        verifyNoInteractions(vectors);
        assertEquals(0, db.jdbc().queryForObject("SELECT COUNT(*) FROM knowledge_document_index_execution", Integer.class));
    }

    @ParameterizedTest
    @ValueSource(strings = {"physical-other", " "})
    void mismatchedOrBlankPersistedTargetCannotAuthorizeVectorWriting(String snapshot) {
        seedJob("INDEXING"); setSnapshot(snapshot);
        var vectors = mock(VectorService.class);
        assertThrows(PipelineException.class, () -> new VectorStoreStep(vectors, store()).process(context("physical-old")));
        verifyNoInteractions(vectors);
        assertEquals(0, db.jdbc().queryForObject("SELECT COUNT(*) FROM knowledge_document_index_execution", Integer.class));
    }

    @Test
    void publicationRequiresTheJobContextAndRegisteredExecutionToKeepTheSameTarget() {
        seedJob("INDEXING"); setSnapshot("physical-old");
        var c = context("physical-old"); c.setFileName("目标文档.txt"); c.setChunks(List.of("已确认的中文正文"));
        var store = store();
        var manifest = DocumentIndexVectorManifest.forExecution("file", "lease", 1);
        assertTrue(store.register(c, manifest)); store.acknowledge("lease"); c.setVectorIds(manifest.batch(0, 1));
        c.setVectorCollectionName("physical-new");
        assertThrows(PipelineException.class, () -> tx().executeWithoutResult(status -> store.lockForPublication(c, 7L)));
        setSnapshot("physical-new");
        assertThrows(PipelineException.class, () -> tx().executeWithoutResult(status -> store.lockForPublication(c, 7L)),
                "Changing job and context together cannot replace the already registered execution target");
        assertEquals("REGISTERED", db.jdbc().queryForObject("SELECT state FROM knowledge_document_index_execution", String.class));
        assertEquals(0, db.jdbc().queryForObject("SELECT COUNT(*) FROM knowledge_file_info", Integer.class));
        setSnapshot("physical-old"); c.setVectorCollectionName("physical-old");
        var metadata = new MetadataPersistStep(db.mapper(KnowledgeBaseRepository.class), db.mapper(FileInfoRepository.class),
                db.mapper(ChunkRepository.class), new com.fasterxml.jackson.databind.ObjectMapper(),
                new DocumentImportPublicationGuard(db.mapper(DocumentImportJobRepository.class), store, org.mockito.Mockito.mock(com.enterprise.ai.service.impl.KnowledgeFileDeletionService.class)), org.mockito.Mockito.mock(com.enterprise.ai.service.impl.KnowledgeTagService.class), org.mockito.Mockito.mock(com.enterprise.ai.service.impl.KnowledgeQuestionService.class));
        tx().executeWithoutResult(status -> metadata.process(c));
        assertTrue(c.isImportPublished());
        assertEquals("COMPLETED", db.jdbc().queryForObject("SELECT status FROM knowledge_document_import_job", String.class));
        assertEquals("physical-old", db.jdbc().queryForObject("SELECT collection_name FROM knowledge_chunk", String.class));
        assertEquals("已确认的中文正文", db.jdbc().queryForObject("SELECT content FROM knowledge_chunk", String.class));
        assertEquals("目标文档.txt", db.jdbc().queryForObject("SELECT file_name FROM knowledge_file_info", String.class));
    }

    @ParameterizedTest
    @ValueSource(strings = {"retry-current", "retry-unknown", "retry-reused", "commit-current", "commit-unknown", "commit-reused"})
    void manualMutationsPreserveTheSubmissionTargetAndRejectUnknownOrRebuiltTargets(String scenario) {
        boolean retry = scenario.startsWith("retry");
        String originalStatus = retry ? "FAILED" : "PARSED";
        seedJob(originalStatus);
        if (!scenario.endsWith("unknown")) setSnapshot("physical-old");
        if (scenario.endsWith("reused")) {
            db.jdbc().update("DELETE FROM knowledge_base WHERE id=7"); seedKnowledgeBase("physical-new");
        }
        var service = service(mock(DocumentArtifactStore.class), mock(DocumentParseRouter.class));
        var access = new DocumentImportAccessContext("default", "42", null, null, null);
        Runnable command = () -> tx().executeWithoutResult(status -> {
            if (retry) service.retry("job", access); else service.commit("job", access);
        });
        if (scenario.endsWith("current")) {
            command.run();
            assertEquals(retry ? "QUEUED" : "PARSED", db.jdbc().queryForObject("SELECT status FROM knowledge_document_import_job", String.class));
            assertEquals(retry ? 0 : 1, db.jdbc().queryForObject("SELECT auto_commit FROM knowledge_document_import_job", Integer.class));
        } else {
            assertThrows(IllegalStateException.class, command::run);
            assertEquals(originalStatus, db.jdbc().queryForObject("SELECT status FROM knowledge_document_import_job", String.class));
            assertEquals(0, db.jdbc().queryForObject("SELECT auto_commit FROM knowledge_document_import_job", Integer.class));
        }
        assertEquals(scenario.endsWith("unknown") ? null : "physical-old",
                db.jdbc().queryForObject("SELECT vector_collection_name FROM knowledge_document_import_job", String.class));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void submissionAndReparsePersistTheServerResolvedPhysicalSnapshot(boolean reparse) throws Exception {
        var artifacts = mock(DocumentArtifactStore.class);
        when(artifacts.open("old-source")).thenReturn(new java.io.ByteArrayInputStream(new byte[]{1, 2, 3}));
        var router = mock(DocumentParseRouter.class); when(router.detect(any())).thenReturn(DocumentFormat.TXT);
        var service = service(artifacts, router);
        var access = new DocumentImportAccessContext("default", "42", "default", null, "WORKSPACE");
        if (reparse) {
            db.jdbc().update("INSERT INTO knowledge_file_info(file_id,knowledge_base_id,file_name,file_size,source_object_key,source_content_type,record_generation) VALUES ('old-file',7,'快照文档.txt',3,'old-source','text/plain',REPEAT('a',32))");
            assertEquals("快照文档.txt", db.jdbc().queryForObject("SELECT file_name FROM knowledge_file_info", String.class));
            tx().executeWithoutResult(status -> service.reparse("old-file", access));
        } else {
            var file = new org.springframework.mock.web.MockMultipartFile("file", "快照文档.txt", "text/plain", "源正文".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            tx().executeWithoutResult(status -> service.submit(file, "kb", "fixed_length", 100, 10,
                    Map.of("vectorCollectionName", "caller-target"), false, access));
        }
        var job = db.mapper(DocumentImportJobRepository.class).selectOne(null);
        assertEquals("physical-old", job.getVectorCollectionName());
        assertEquals("kb", job.getKnowledgeBaseCode()); assertEquals(7L, job.getKnowledgeBaseId());
        assertEquals("快照文档.txt", job.getFileName());
        assertEquals(reparse ? "old-file" : null, job.getReplaceFileId());
        assertEquals(reparse ? db.jdbc().queryForObject("SELECT id FROM knowledge_file_info WHERE file_id='old-file'", Long.class) : null, job.getReplaceFileRowId());
        assertEquals(reparse ? db.jdbc().queryForObject("SELECT record_generation FROM knowledge_file_info WHERE file_id='old-file'", String.class) : null, job.getReplaceFileGeneration());
        if (!reparse) assertTrue(job.getExtraParamsJson().contains("caller-target"));
    }

    @Test
    void genericJobUpdateCannotOverwriteTheSubmissionSnapshot() {
        seedJob("FAILED"); setSnapshot("physical-old");
        var jobs = db.mapper(DocumentImportJobRepository.class); var job = jobs.selectOne(null);
        job.setVectorCollectionName("replacement"); job.setErrorMessage("新的错误说明");
        job.setReplaceFileId("untrusted-file"); job.setReplaceFileRowId(999L);
        tx().executeWithoutResult(status -> jobs.updateById(job));
        assertEquals("physical-old", jobs.selectById(job.getId()).getVectorCollectionName());
        assertEquals("新的错误说明", jobs.selectById(job.getId()).getErrorMessage());
        assertNull(jobs.selectById(job.getId()).getReplaceFileId()); assertNull(jobs.selectById(job.getId()).getReplaceFileRowId());
    }

    @ParameterizedTest
    @ValueSource(strings = {"CANCELLED", "COMPLETED", "FAILED"})
    void explicitFileIdSubmissionKeepsHistoryAndRespectsTheActiveReservation(String previousStatus) {
        seedJob(previousStatus);
        var router = mock(DocumentParseRouter.class); when(router.detect(any())).thenReturn(DocumentFormat.TXT);
        var service = service(mock(DocumentArtifactStore.class), router);
        var file = new org.springframework.mock.web.MockMultipartFile("file", "重新提交.txt", "text/plain", "新的源正文".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var access = new DocumentImportAccessContext("default", "42", "default", null, "WORKSPACE");
        Runnable submit = () -> tx().executeWithoutResult(status -> service.submitWithFileId(file, "kb", "file", "fixed_length", 100, 10, Map.of(), false, access));
        if (previousStatus.equals("FAILED")) assertThrows(IllegalStateException.class, submit::run);
        else {
            submit.run();
            assertEquals("重新提交.txt", db.jdbc().queryForObject("SELECT file_name FROM knowledge_document_import_job WHERE job_id<>'job'", String.class));
            assertEquals("physical-old", db.jdbc().queryForObject("SELECT vector_collection_name FROM knowledge_document_import_job WHERE job_id<>'job'", String.class));
        }
        assertEquals(previousStatus.equals("FAILED") ? 1 : 2, db.jdbc().queryForObject("SELECT COUNT(*) FROM knowledge_document_import_job", Integer.class));
        assertEquals(previousStatus, db.jdbc().queryForObject("SELECT status FROM knowledge_document_import_job WHERE job_id='job'", String.class));
    }

    private void seedKnowledgeBase(String physical) {
        db.jdbc().update("INSERT INTO knowledge_base(id,name,code,vector_collection_name) VALUES (7,'target','kb',?)", physical);
    }

    private void seedJob(String status) {
        db.jdbc().update("INSERT INTO knowledge_document_import_job(job_id,file_id,knowledge_base_id,knowledge_base_code,created_by_actor_id,file_name,file_type,source_object_key,provider_type,status,stage,lease_owner,lease_until,auto_commit) VALUES ('job','file',7,'kb','42','file.txt','txt','source','JAVA_FAST',?,'INDEXING','lease','2099-01-01 00:00:00',0)", status);
    }

    private void setSnapshot(String physical) {
        db.jdbc().update("UPDATE knowledge_document_import_job SET vector_collection_name=? WHERE job_id='job'", physical);
    }

    private TransactionTemplate tx() { return new TransactionTemplate(new DataSourceTransactionManager(db.jdbc().getDataSource())); }

    private DocumentImportJobServiceImpl service(DocumentArtifactStore artifacts, DocumentParseRouter router) {
        var properties = new DocumentImportJobProperties(); properties.setEnabled(false);
        return new DocumentImportJobServiceImpl(db.mapper(DocumentImportJobRepository.class), db.mapper(KnowledgeBaseRepository.class), db.mapper(FileInfoRepository.class), artifacts, router, new com.fasterxml.jackson.databind.ObjectMapper(), properties, mock(DocumentImportJobWorker.class), mock(TextCleanStep.class), mock(ChunkStep.class), Runnable::run, new org.springframework.jdbc.datasource.DataSourceTransactionManager(db.jdbc().getDataSource()));
    }

    private DocumentIndexExecutionStore store() {
        return KnowledgeIndexTestSupport.executions(db);
    }

    private static PipelineContext context(String physical) {
        var c = new PipelineContext(); c.setKnowledgeBaseId(7L); c.setKnowledgeBaseCode("kb"); c.setVectorCollectionName(physical);
        c.setImportJobId("job"); c.setImportLeaseOwner("lease"); c.setFileId("file");
        c.setChunks(List.of("text")); c.setVectors(List.of(List.of(1.0f, 0.0f)));
        return c;
    }
}
