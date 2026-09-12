package com.enterprise.ai.service.impl;

import com.enterprise.ai.domain.dto.KnowledgeImportRequest;
import com.enterprise.ai.embedding.EmbeddingService;
import com.enterprise.ai.pipeline.PipelineContext;
import com.enterprise.ai.pipeline.PipelineException;
import com.enterprise.ai.pipeline.document.artifact.DocumentArtifactStore;
import com.enterprise.ai.pipeline.document.job.*;
import com.enterprise.ai.pipeline.step.VectorStoreStep;
import com.enterprise.ai.repository.*;
import com.enterprise.ai.retrieval.KnowledgeRetrievalEngine;
import com.enterprise.ai.support.KnowledgeQueryTestDatabase;
import com.enterprise.ai.vector.VectorService;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class KnowledgeFileRetirementTest {
    private KnowledgeQueryTestDatabase db;
    private KnowledgeServiceImpl service;
    private VectorService vectors;
    private DocumentArtifactStore artifacts;
    private TransactionTemplate tx;
    private DocumentIndexExecutionStore executions;
    private KnowledgeFileDeletionService deletion;
    private final Set<String> remote = ConcurrentHashMap.newKeySet();
    private Runnable afterUpsert = () -> {};

    @BeforeEach
    void setup() throws Exception {
        db = new KnowledgeQueryTestDatabase(List.of("knowledge_base", "knowledge_file_info", "knowledge_chunk", "knowledge_document_import_job", "knowledge_document_index_execution", "knowledge_user_file_permission", "knowledge_question"),
                KnowledgeBaseRepository.class, FileInfoRepository.class, ChunkRepository.class, DocumentImportJobRepository.class, DocumentIndexExecutionRepository.class, UserFilePermissionRepository.class, KnowledgeQuestionRepository.class);
        db.jdbc().update("INSERT INTO knowledge_base(id,name,code,vector_collection_name,dimension,embedding_model_instance_id) VALUES (7,'删除测试知识库','kb','physical_kb',2,'model')");
        vectors = mock(VectorService.class); artifacts = mock(DocumentArtifactStore.class);
        var embedding = mock(EmbeddingService.class);
        when(embedding.embedBatch(anyString(), anyList())).thenAnswer(call -> Collections.nCopies(call.<List<String>>getArgument(1).size(), List.of(1f, 0f)));
        doAnswer(call -> { remote.addAll(call.getArgument(1)); afterUpsert.run(); return null; })
                .when(vectors).upsert(anyString(), anyList(), anyList(), anyList(), anyList());
        doAnswer(call -> { remote.remove(call.getArgument(1)); return null; }).when(vectors).deleteById(anyString(), anyString());
        var bases = db.mapper(KnowledgeBaseRepository.class);
        deletion = KnowledgeIndexTestSupport.deletion(db, artifacts, db.mapper(UserFilePermissionRepository.class), db.mapper(KnowledgeQuestionRepository.class));
        service = KnowledgeManagementTestServices.create(db.mapper(FileInfoRepository.class), db.mapper(ChunkRepository.class), mock(KnowledgeTagRepository.class), org.mockito.Mockito.mock(com.enterprise.ai.service.impl.KnowledgeQuestionService.class), mock(KnowledgeOperationsQuery.class), KnowledgeIndexTestSupport.writer(db, embedding, vectors), new KnowledgeBaseLookup(bases), mock(KnowledgeRetrievalEngine.class), deletion, org.mockito.Mockito.mock(com.enterprise.ai.service.impl.KnowledgeBaseLifecycleService.class), org.mockito.Mockito.mock(com.enterprise.ai.service.impl.KnowledgeTagService.class));
        tx = new TransactionTemplate(new DataSourceTransactionManager(db.jdbc().getDataSource()));
        executions = KnowledgeIndexTestSupport.executions(db);
    }

    @AfterEach
    void close() { if (db != null) db.close(); }

    @ParameterizedTest
    @ValueSource(strings = {"by-code", "by-file"})
    void fileDeletionCommitsDurableReclamationWithoutRemoteIo(String mode) {
        importFile("file", "待删除正文");
        Set<String> old = Set.copyOf(remote);
        clearInvocations(vectors);
        tx.executeWithoutResult(status -> {
            if (mode.equals("by-code")) service.deleteByFileId("kb", "file");
            else service.deleteFileById("file");
        });
        verifyNoInteractions(vectors);
        assertEquals(old, remote, "Deletion must retain vectors until a committed cleanup intent is consumed");
        assertEquals(0, count("knowledge_file_info")); assertEquals(0, count("knowledge_chunk"));
        assertEquals("RECLAIMING", db.jdbc().queryForObject("SELECT state FROM knowledge_document_index_execution", String.class));
        reclaimer().reclaimPending();
        assertTrue(remote.isEmpty());
        assertEquals("RECLAIMED", db.jdbc().queryForObject("SELECT state FROM knowledge_document_index_execution", String.class));
    }

    @Test
    void aRegisteredJobCannotPublishAfterItsFileWasDeletedDuringUpsert() {
        var context = jobContext("file");
        afterUpsert = () -> tx.executeWithoutResult(status -> service.deleteByFileId("kb", "file"));
        new VectorStoreStep(vectors, executions).process(context);
        assertThrows(PipelineException.class, () -> tx.executeWithoutResult(status -> KnowledgeIndexTestSupport.metadata(db, executions).process(context)),
                "A file deletion must revoke the registered job before a late publication");
        assertEquals("CANCELLED", db.jdbc().queryForObject("SELECT status FROM knowledge_document_import_job", String.class));
        assertEquals(0, count("knowledge_file_info")); assertEquals(0, count("knowledge_chunk"));
        reclaimer().reclaimPending(); assertTrue(remote.isEmpty());
    }

    @Test
    void aRolledBackDeletionPreservesThePublishedVectorAndMetadata() {
        importFile("file", "回滚后保留正文");
        seedAssociations();
        Long originalChunk = db.jdbc().queryForObject("SELECT chunk_id FROM knowledge_question", Long.class);
        Set<String> old = Set.copyOf(remote);
        db.addInterceptor(new FailAfterFileDelete());
        assertThrows(RuntimeException.class, () -> tx.executeWithoutResult(status -> service.deleteByFileId("kb", "file")));
        assertEquals(old, remote, "A rolled-back metadata deletion must preserve published vectors");
        assertEquals(1, count("knowledge_file_info")); assertEquals(1, count("knowledge_chunk"));
        assertEquals("PUBLISHED", db.jdbc().queryForObject("SELECT state FROM knowledge_document_index_execution", String.class));
        assertEquals(1, count("knowledge_user_file_permission"));
        assertEquals(originalChunk, db.jdbc().queryForObject("SELECT chunk_id FROM knowledge_question", Long.class));
        verifyNoInteractions(artifacts);
    }

    @Test
    void failureAndReconstructionResumeOnlyTheRetiredGeneration() {
        importFile("file", "旧正文");
        Set<String> old = Set.copyOf(remote);
        service.deleteFileById("file");
        importFile("file", "重建后的正文");
        String current = db.jdbc().queryForObject("SELECT vector_id FROM knowledge_chunk", String.class);
        doThrow(new IllegalStateException("remote unavailable")).when(vectors).deleteById("physical_kb", old.iterator().next());
        reclaimer().reclaimPending();
        assertTrue(remote.containsAll(old)); assertTrue(remote.contains(current));
        doAnswer(call -> { remote.remove(call.getArgument(1)); return null; }).when(vectors).deleteById(anyString(), anyString());
        makeDue();
        new DocumentIndexReclaimer(db.mapper(DocumentIndexExecutionRepository.class), KnowledgeIndexTestSupport.executions(db), vectors,
                new DocumentImportJobProperties()).reclaimPending();
        assertEquals(Set.of(current), remote);
        assertEquals("重建后的正文", db.jdbc().queryForObject("SELECT content FROM knowledge_chunk", String.class));
    }

    @Test
    void aGeneratedManifestRetainsVectorsStillReferencedByAnotherFile() {
        importFile("file", "共享向量的原始正文");
        String vector = remote.iterator().next();
        db.jdbc().update("INSERT INTO knowledge_file_info(file_id,knowledge_base_id,file_name,record_generation) VALUES ('shared',7,'共享文件.txt',REPEAT('e',32))");
        db.jdbc().update("INSERT INTO knowledge_chunk(file_id,knowledge_base_id,content,chunk_index,vector_id,collection_name) VALUES ('shared',7,'共享正文',0,?,'physical_kb')", vector);
        service.deleteFileById("file"); reclaimer().reclaimPending();
        assertEquals(Set.of(vector), remote);
        assertEquals("RECLAIMING", db.jdbc().queryForObject("SELECT state FROM knowledge_document_index_execution", String.class));
        service.deleteFileById("shared"); makeDue(); reclaimer().reclaimPending();
        assertTrue(remote.isEmpty());
    }

    @Test
    void legacyReferencesKeepTheirExactKeysAndUnknownCompletionState() {
        importFile("file", "历史正文");
        String legacy = "历史\\主键\" || id != \"";
        db.jdbc().update("UPDATE knowledge_chunk SET vector_id=?,collection_name='legacy_collection'", legacy);
        remote.add(legacy);
        service.deleteFileById("file");
        assertEquals(legacy, db.jdbc().queryForObject("SELECT single_vector_id FROM knowledge_document_index_execution WHERE operation_type='RETIRED_VECTOR'", String.class));
        reclaimer().reclaimPending();
        verify(vectors).deleteById("legacy_collection", legacy);
        assertTrue(remote.isEmpty());
        assertEquals("RECLAIMING", db.jdbc().queryForObject("SELECT state FROM knowledge_document_index_execution WHERE operation_type='RETIRED_VECTOR'", String.class));
        makeDue(); reclaimer().reclaimPending();
        verify(vectors, times(2)).deleteById("legacy_collection", legacy);
    }

    @Test
    void deletionRevokesFilePermissionsAndUnlinksQuestionsWithoutDroppingQuestionText() {
        importFile("file", "原始正文"); seedAssociations();
        var permissionService = new com.enterprise.ai.security.impl.PermissionServiceImpl(db.mapper(UserFilePermissionRepository.class), new DataSourceTransactionManager(db.jdbc().getDataSource()));
        assertEquals(List.of("file"), permissionService.getAccessibleFileIds("actor"));
        service.deleteByFileId("kb", "file");
        importFile("file", "重建正文");
        assertEquals(List.of(), permissionService.getAccessibleFileIds("actor"));
        assertEquals(1, count("knowledge_question"));
        assertNull(db.jdbc().queryForObject("SELECT chunk_id FROM knowledge_question", Long.class));
        assertEquals("保留的问题正文", db.jdbc().queryForObject("SELECT question FROM knowledge_question", String.class));
    }

    @ParameterizedTest
    @ValueSource(strings = {"QUEUED", "PARSING", "PARSED", "INDEXING", "RETRY_WAIT", "FAILED"})
    void allRetryableOrPendingIntentsLoseTheirFileReservationOnDeletion(String stage) {
        var context = jobContext("file");
        db.jdbc().update("UPDATE knowledge_document_import_job SET status=?,stage=?", stage, stage);
        assertEquals("file", db.jdbc().queryForObject("SELECT active_file_id FROM knowledge_document_import_job", String.class));
        service.deleteByFileId("kb", "file");
        assertEquals("CANCELLED", db.jdbc().queryForObject("SELECT status FROM knowledge_document_import_job", String.class));
        assertNull(db.jdbc().queryForObject("SELECT active_file_id FROM knowledge_document_import_job", String.class));
        assertEquals(0, db.mapper(DocumentImportJobRepository.class).finalizeParsing("job", context.getImportLeaseOwner(), "JAVA_FAST", "v", "late-key", java.time.LocalDateTime.now()));
    }

    @Test
    void replacementPublicationAndOriginalRetirementCommitTogether() {
        importFile("file", "原始正文"); seedAssociations();
        var context = replacementContext("replace");
        new VectorStoreStep(vectors, executions).process(context);
        publish(context);
        assertTrue(context.isImportPublished());
        assertEquals("COMPLETED", db.jdbc().queryForObject("SELECT status FROM knowledge_document_import_job", String.class));
        assertEquals("new-replace", db.jdbc().queryForObject("SELECT file_id FROM knowledge_file_info", String.class));
        assertEquals("RECLAIMING", db.jdbc().queryForObject("SELECT state FROM knowledge_document_index_execution WHERE file_id='file'", String.class));
        assertEquals(0, count("knowledge_user_file_permission"));
        assertNull(db.jdbc().queryForObject("SELECT chunk_id FROM knowledge_question", Long.class));
        reclaimer().reclaimPending(); assertEquals(Set.copyOf(context.getVectorIds()), remote);
    }

    @Test
    void replacementRollbackRestoresTheOriginalAndDoesNotPublishTheNewFile() {
        importFile("file", "原始正文"); seedAssociations();
        var context = replacementContext("replace");
        new VectorStoreStep(vectors, executions).process(context);
        db.addInterceptor(new FailAfterFileDelete());
        assertThrows(RuntimeException.class, () -> publish(context));
        assertFalse(context.isImportPublished());
        assertEquals("file", db.jdbc().queryForObject("SELECT file_id FROM knowledge_file_info", String.class));
        assertEquals("原始正文", db.jdbc().queryForObject("SELECT content FROM knowledge_chunk", String.class));
        assertEquals("PUBLISHED", db.jdbc().queryForObject("SELECT state FROM knowledge_document_index_execution WHERE file_id='file'", String.class));
        assertEquals("REGISTERED", db.jdbc().queryForObject("SELECT state FROM knowledge_document_index_execution WHERE file_id='new-replace'", String.class));
        assertEquals(1, count("knowledge_user_file_permission"));
        assertNotNull(db.jdbc().queryForObject("SELECT chunk_id FROM knowledge_question", Long.class));
        verifyNoInteractions(artifacts);
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing", "wrong-generation"})
    void invalidReplacementSnapshotsAreRejectedBeforeExternalWriting(String mode) {
        importFile("file", "原始正文");
        var context = replacementContext("replace");
        db.jdbc().update("UPDATE knowledge_document_import_job SET replace_file_row_id=?", mode.equals("missing") ? null : -1L);
        clearInvocations(vectors);
        assertThrows(PipelineException.class, () -> new VectorStoreStep(vectors, executions).process(context));
        verifyNoInteractions(vectors);
        assertEquals(1, count("knowledge_document_index_execution"));
        assertEquals("file", db.jdbc().queryForObject("SELECT file_id FROM knowledge_file_info", String.class));
    }

    @Test
    void lateReplacementCannotDeleteANewFileWithTheSameBusinessId() {
        importFile("file", "原始正文");
        var context = replacementContext("replace");
        new VectorStoreStep(vectors, executions).process(context);
        db.jdbc().update("DELETE FROM knowledge_chunk"); db.jdbc().update("DELETE FROM knowledge_file_info");
        db.jdbc().update("INSERT INTO knowledge_file_info(file_id,knowledge_base_id,file_name) VALUES ('file',7,'重建文件.txt')");
        db.jdbc().update("INSERT INTO knowledge_chunk(file_id,knowledge_base_id,content,chunk_index,vector_id,collection_name) VALUES ('file',7,'重建正文',0,'new-generation-vector','physical_kb')");
        remote.add("new-generation-vector");
        assertThrows(PipelineException.class, () -> publish(context));
        assertEquals("重建正文", db.jdbc().queryForObject("SELECT content FROM knowledge_chunk", String.class));
        assertEquals(1, db.mapper(DocumentImportJobRepository.class).failIndexing(context.getImportJobId(), context.getImportLeaseOwner(), "stale replacement"));
        reclaimer().reclaimPending();
        assertTrue(remote.contains("new-generation-vector"));
        assertTrue(Collections.disjoint(remote, context.getVectorIds()));
    }

    @Test
    void theFirstCommittedReplacementCancelsOtherRegisteredReplacements() {
        importFile("file", "原始正文");
        var first = replacementContext("first"); var second = replacementContext("second");
        new VectorStoreStep(vectors, executions).process(first); new VectorStoreStep(vectors, executions).process(second);
        publish(first);
        assertThrows(PipelineException.class, () -> publish(second));
        assertEquals("CANCELLED", db.jdbc().queryForObject("SELECT status FROM knowledge_document_import_job WHERE job_id='second'", String.class));
        reclaimer().reclaimPending(); assertEquals(Set.copyOf(first.getVectorIds()), remote);
    }

    @Test
    void explicitDeletionAlsoRevokesRegisteredJobsReplacingThatFile() {
        importFile("file", "原始正文");
        var context = replacementContext("replace");
        new VectorStoreStep(vectors, executions).process(context);
        service.deleteFileById("file");
        assertThrows(PipelineException.class, () -> publish(context));
        assertEquals("CANCELLED", db.jdbc().queryForObject("SELECT status FROM knowledge_document_import_job", String.class));
        reclaimer().reclaimPending(); assertTrue(remote.isEmpty());
    }

    @Test
    void activeFileReservationsAreGlobalAndReleasedWithoutRemovingJobHistory() {
        jobContext("file");
        String other = "INSERT INTO knowledge_document_import_job(job_id,file_id,knowledge_base_id,knowledge_base_code,vector_collection_name,file_name,file_type,source_object_key,provider_type,status,stage) VALUES ('other','file',8,'other','physical_other','另一份文件.txt','txt','other/source','JAVA_FAST','QUEUED','QUEUED')";
        assertThrows(org.springframework.dao.DuplicateKeyException.class, () -> db.jdbc().update(other));
        service.deleteByFileId("kb", "file");
        db.jdbc().update(other);
        assertEquals(2, count("knowledge_document_import_job"));
        assertEquals(1, db.jdbc().queryForObject("SELECT COUNT(*) FROM knowledge_document_import_job WHERE active_file_id='file'", Integer.class));
    }

    private PipelineContext replacementContext(String jobId) {
        Long original = db.jdbc().queryForObject("SELECT id FROM knowledge_file_info WHERE file_id='file'", Long.class);
        var context = jobContext(jobId, "new-" + jobId);
        String generation = db.jdbc().queryForObject("SELECT record_generation FROM knowledge_file_info WHERE file_id='file'", String.class);
        db.jdbc().update("UPDATE knowledge_document_import_job SET replace_file_id='file',replace_file_row_id=?,replace_file_generation=? WHERE job_id=?", original, generation, jobId);
        return context;
    }

    @Test
    void aRegisteredReplacementCannotPublishAgainstAReusedFilePrimaryKey() {
        importFile("file", "原始正文");
        Long original = db.jdbc().queryForObject("SELECT id FROM knowledge_file_info WHERE file_id='file'", Long.class);
        var context = replacementContext("replace");
        new VectorStoreStep(vectors, executions).process(context);
        replaceUsingPrimaryKey(original);
        assertThrows(PipelineException.class, () -> publish(context),
                "A replacement job must not publish against another file reusing the original primary key");
        assertEquals("复用主键的新正文", db.jdbc().queryForObject("SELECT content FROM knowledge_chunk WHERE file_id='file'", String.class));
    }

    @Test
    void replacementRetirementCannotDeleteAReusedFilePrimaryKey() {
        importFile("file", "原始正文");
        Long original = db.jdbc().queryForObject("SELECT id FROM knowledge_file_info WHERE file_id='file'", Long.class);
        replacementContext("replace");
        var job = db.mapper(DocumentImportJobRepository.class).selectOne(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<com.enterprise.ai.domain.entity.DocumentImportJob>()
                .eq(com.enterprise.ai.domain.entity.DocumentImportJob::getJobId, "replace"));
        replaceUsingPrimaryKey(original);
        assertThrows(IllegalStateException.class, () -> tx.executeWithoutResult(status -> deletion.retireReplacement(job)),
                "A delayed replacement retirement must not delete a new file with the original primary key");
        assertEquals("复用主键的新正文", db.jdbc().queryForObject("SELECT content FROM knowledge_chunk WHERE file_id='file'", String.class));
    }

    private void replaceUsingPrimaryKey(Long id) {
        db.jdbc().update("DELETE FROM knowledge_chunk WHERE file_id='file'");
        db.mapper(FileInfoRepository.class).deleteById(id);
        var replacement = new com.enterprise.ai.domain.entity.FileInfo(); replacement.setId(id); replacement.setFileId("file");
        replacement.setKnowledgeBaseId(7L); replacement.setFileName("复用主键的新文件.txt"); db.mapper(FileInfoRepository.class).insert(replacement);
        db.jdbc().update("INSERT INTO knowledge_chunk(file_id,knowledge_base_id,content,chunk_index,vector_id,collection_name) VALUES ('file',7,'复用主键的新正文',0,'reused-primary-key-vector','physical_kb')");
    }

    private void publish(PipelineContext context) {
        var metadata = new com.enterprise.ai.pipeline.step.MetadataPersistStep(db.mapper(KnowledgeBaseRepository.class), db.mapper(FileInfoRepository.class),
                db.mapper(ChunkRepository.class), new com.fasterxml.jackson.databind.ObjectMapper(),
                new DocumentImportPublicationGuard(db.mapper(DocumentImportJobRepository.class), executions, deletion), org.mockito.Mockito.mock(com.enterprise.ai.service.impl.KnowledgeTagService.class), org.mockito.Mockito.mock(com.enterprise.ai.service.impl.KnowledgeQuestionService.class));
        tx.executeWithoutResult(status -> metadata.process(context));
    }

    private void seedAssociations() {
        db.jdbc().update("INSERT INTO knowledge_user_file_permission(user_id,file_id) VALUES ('actor','file')");
        db.jdbc().update("INSERT INTO knowledge_question(knowledge_base_id,chunk_id,question) SELECT 7,id,'保留的问题正文' FROM knowledge_chunk WHERE file_id='file'");
    }

    private void makeDue() { db.jdbc().update("UPDATE knowledge_document_index_execution SET next_cleanup_at='2000-01-01 00:00:00' WHERE state='RECLAIMING'"); }

    private void importFile(String fileId, String content) {
        var request = new KnowledgeImportRequest(); request.setKnowledgeBaseCode("kb"); request.setFileId(fileId);
        request.setFileName("文件.txt"); request.setChunks(List.of(content)); service.importChunks(request);
    }

    private PipelineContext jobContext(String fileId) {
        return jobContext("job", fileId);
    }

    private PipelineContext jobContext(String jobId, String fileId) {
        String lease = UUID.randomUUID().toString();
        db.jdbc().update("INSERT INTO knowledge_document_import_job(job_id,file_id,knowledge_base_id,knowledge_base_code,vector_collection_name,file_name,file_type,source_object_key,provider_type,status,stage,lease_owner,lease_until) VALUES (?,?,7,'kb','physical_kb','任务.txt','txt','job/source','JAVA_FAST','INDEXING','INDEXING',?,DATEADD('SECOND',1200,CURRENT_TIMESTAMP))", jobId, fileId, lease);
        var context = new PipelineContext(); context.setKnowledgeBaseId(7L); context.setKnowledgeBaseCode("kb");
        context.setVectorCollectionName("physical_kb"); context.setKnowledgeBaseDimension(2); context.setFileId(fileId);
        context.setFileName("任务.txt"); context.setChunks(List.of("任务正文")); context.setVectors(List.of(List.of(1f, 0f)));
        context.setImportJobId(jobId); context.setImportLeaseOwner(lease); return context;
    }

    private int count(String table) { return db.jdbc().queryForObject("SELECT COUNT(*) FROM " + table, Integer.class); }
    private DocumentIndexReclaimer reclaimer() { return new DocumentIndexReclaimer(db.mapper(DocumentIndexExecutionRepository.class), executions, vectors, new DocumentImportJobProperties()); }

    @org.apache.ibatis.plugin.Intercepts(@org.apache.ibatis.plugin.Signature(type=org.apache.ibatis.executor.Executor.class,method="update",args={org.apache.ibatis.mapping.MappedStatement.class,Object.class}))
    static class FailAfterFileDelete implements org.apache.ibatis.plugin.Interceptor {
        public Object intercept(org.apache.ibatis.plugin.Invocation invocation) throws Throwable {
            Object result = invocation.proceed();
            var statement = (org.apache.ibatis.mapping.MappedStatement) invocation.getArgs()[0];
            if (statement.getId().contains("FileInfoRepository.") && statement.getSqlCommandType() == org.apache.ibatis.mapping.SqlCommandType.DELETE
                    && result instanceof Number changed && changed.intValue() > 0)
                throw new IllegalStateException("Injected failure after file metadata deletion");
            return result;
        }
    }
}
