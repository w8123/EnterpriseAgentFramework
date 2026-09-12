package com.enterprise.ai.service.impl;

import com.enterprise.ai.domain.dto.KnowledgeQuestionRequest;
import com.enterprise.ai.pipeline.PipelineContext;
import com.enterprise.ai.pipeline.document.artifact.DocumentArtifactStore;
import com.enterprise.ai.pipeline.document.job.DocumentImportPublicationGuard;
import com.enterprise.ai.pipeline.document.job.DocumentIndexExecutionStore;
import com.enterprise.ai.pipeline.step.MetadataPersistStep;
import com.enterprise.ai.repository.*;
import com.enterprise.ai.retrieval.KnowledgeRetrievalEngine;
import com.enterprise.ai.support.KnowledgeQueryTestDatabase;
import com.enterprise.ai.vector.VectorService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.plugin.*;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class KnowledgeQuestionLifecycleTest {
    private KnowledgeQueryTestDatabase db;
    private DataSourceTransactionManager manager;
    private KnowledgeServiceImpl service;
    private KnowledgeFileDeletionService files;
    private KnowledgeBaseLifecycleService bases;
    private KnowledgeQuestionService questions;

    @BeforeEach
    void setUp() throws Exception {
        db = new KnowledgeQueryTestDatabase(List.of("knowledge_base", "knowledge_file_info", "knowledge_chunk",
                "knowledge_question", "knowledge_tag", "knowledge_document_import_job", "knowledge_document_index_execution",
                "knowledge_user_file_permission"), KnowledgeBaseRepository.class, FileInfoRepository.class,
                ChunkRepository.class, KnowledgeQuestionRepository.class, KnowledgeTagRepository.class,
                DocumentImportJobRepository.class, DocumentIndexExecutionRepository.class, UserFilePermissionRepository.class);
        db.jdbc().execute("SET DEFAULT_LOCK_TIMEOUT 10000");
        manager = new DataSourceTransactionManager(db.jdbc().getDataSource());
        questions = new KnowledgeQuestionService(db.mapper(KnowledgeBaseRepository.class), db.mapper(ChunkRepository.class),
                db.mapper(KnowledgeQuestionRepository.class), manager);
        db.jdbc().update("INSERT INTO knowledge_base(id,code,name,vector_collection_name) VALUES (7,'kb','问题目标','physical'),(8,'other','其他知识库','other_physical')");
        db.jdbc().update("INSERT INTO knowledge_file_info(id,file_id,knowledge_base_id,file_name) VALUES (11,'file-1',7,'原文件'),(12,'keep',8,'其他文件')");
        db.jdbc().update("INSERT INTO knowledge_chunk(id,file_id,knowledge_base_id,chunk_index,content) VALUES (101,'file-1',7,0,'原片段'),(201,'keep',8,0,'其他片段')");
        files = new KnowledgeFileDeletionService(db.mapper(KnowledgeBaseRepository.class), db.mapper(FileInfoRepository.class),
                db.mapper(ChunkRepository.class), db.mapper(DocumentImportJobRepository.class), db.mapper(DocumentIndexExecutionRepository.class),
                mock(DocumentIndexExecutionStore.class), mock(DocumentArtifactStore.class), db.mapper(UserFilePermissionRepository.class),
                questions, manager, mock(KnowledgeTagService.class));
        bases = new KnowledgeBaseLifecycleService(db.mapper(KnowledgeBaseRepository.class), db.mapper(KnowledgeTagRepository.class),
                questions, files, mock(KnowledgeCollectionLifecycleStore.class), mock(VectorService.class), manager);
        service = KnowledgeManagementTestServices.create(db.mapper(FileInfoRepository.class),
                db.mapper(ChunkRepository.class), db.mapper(KnowledgeTagRepository.class), questions,
                mock(KnowledgeOperationsQuery.class), mock(KnowledgeIndexWriteService.class),
                new KnowledgeBaseLookup(db.mapper(KnowledgeBaseRepository.class)), mock(KnowledgeRetrievalEngine.class),
                files, bases, mock(KnowledgeTagService.class));
    }

    @AfterEach
    void close() { if (db != null) db.close(); }

    @Test
    void fileRetirementCannotMissAQuestionThatAlreadyResolvedItsChunk() throws Exception {
        raceRetirement(() -> files.deleteByFileId("kb", "file-1"));
        assertNull(db.jdbc().queryForObject("SELECT chunk_id FROM knowledge_question WHERE knowledge_base_id=7", Long.class),
                "A concurrent question must not retain the deleted chunk identity");
        assertEquals("并发问题", db.jdbc().queryForObject("SELECT question FROM knowledge_question WHERE knowledge_base_id=7", String.class));
    }

    @Test
    void baseRetirementCannotMissAQuestionThatAlreadyResolvedItsOwner() throws Exception {
        raceRetirement(() -> bases.deleteByCode("kb"));
        assertEquals(0, db.jdbc().queryForObject("SELECT COUNT(*) FROM knowledge_question WHERE knowledge_base_id=7", Integer.class),
                "A retired knowledge base cannot acquire a late question");
    }

    @Test
    void replacingAnImportAttemptDetachesOldQuestionReferencesWithoutDeletingTheirText() {
        db.jdbc().update("UPDATE knowledge_file_info SET import_job_id='same-job' WHERE file_id='file-1'");
        db.jdbc().update("INSERT INTO knowledge_question(id,knowledge_base_id,chunk_id,question,hit_count) VALUES (1,7,101,'保留的问题',17)");
        var metadata = new MetadataPersistStep(db.mapper(KnowledgeBaseRepository.class), db.mapper(FileInfoRepository.class),
                db.mapper(ChunkRepository.class), new ObjectMapper(), mock(DocumentImportPublicationGuard.class), mock(KnowledgeTagService.class), questions);
        var context = new PipelineContext(); context.setKnowledgeBaseId(7L); context.setKnowledgeBaseCode("kb");
        context.setVectorCollectionName("physical"); context.setFileId("file-1"); context.setFileName("新文件");
        context.setImportJobId("same-job"); context.setChunks(List.of("新的片段")); context.setVectorIds(List.of("new-vector"));
        new TransactionTemplate(manager).executeWithoutResult(status -> metadata.process(context));
        assertNull(db.jdbc().queryForObject("SELECT chunk_id FROM knowledge_question WHERE id=1", Long.class),
                "Replaced chunks must not leave dangling question references");
        assertEquals("保留的问题", db.jdbc().queryForObject("SELECT question FROM knowledge_question WHERE id=1", String.class));
        assertEquals(17, db.jdbc().queryForObject("SELECT hit_count FROM knowledge_question WHERE id=1", Integer.class));
    }

    @Test
    void questionsWithoutAChunkKeepDefaultsAndListsRespectTheirOwner() {
        var request = request(null); request.setSource(" ");
        var own = service.createQuestion("kb", request);
        var other = service.createQuestion("other", request(201L));
        assertNull(own.getChunkId()); assertEquals("MANUAL", own.getSource()); assertEquals(0, own.getHitCount());
        assertNotNull(own.getCreateTime()); assertNotNull(own.getUpdateTime());
        assertEquals(List.of(own.getId()), service.listQuestions("kb", null).stream().map(item -> item.getId()).toList());
        assertEquals(List.of(other.getId()), service.listQuestions("other", 201L).stream().map(item -> item.getId()).toList());
        service.deleteQuestion("kb", other.getId());
        assertEquals(1, service.listQuestions("other", null).size());
        service.deleteQuestion("kb", own.getId());
        assertTrue(service.listQuestions("kb", null).isEmpty());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(longs = {0, -1, 201, 999})
    void missingAndForeignChunksCannotBecomeQuestionTargets(long chunkId) {
        assertThrows(IllegalArgumentException.class, () -> service.createQuestion("kb", request(chunkId)));
        assertEquals(0, db.jdbc().queryForObject("SELECT COUNT(*) FROM knowledge_question", Integer.class));
    }

    @Test
    void invalidQuestionValuesAreRejectedBeforeWriting() {
        assertThrows(IllegalArgumentException.class, () -> service.createQuestion("kb", null));
        for (String text : List.of("", " ", "问".repeat(513))) {
            var request = request(null); request.setQuestion(text);
            assertThrows(IllegalArgumentException.class, () -> service.createQuestion("kb", request));
        }
        var request = request(null); request.setSource("s".repeat(33));
        assertThrows(IllegalArgumentException.class, () -> service.createQuestion("kb", request));
        assertEquals(0, db.jdbc().queryForObject("SELECT COUNT(*) FROM knowledge_question", Integer.class));
    }

    @Test
    void anInsertFailureRollsBackTheQuestionAndAllowsRetry() {
        db.addInterceptor(new FailQuestionMutation(".insert"));
        assertThrows(RuntimeException.class, () -> service.createQuestion("kb", request(101L)));
        assertEquals(0, db.jdbc().queryForObject("SELECT COUNT(*) FROM knowledge_question", Integer.class));
        var saved = service.createQuestion("kb", request(101L));
        assertEquals("验证问题", saved.getQuestion()); assertEquals(101L, saved.getChunkId());
        assertEquals(1, db.jdbc().queryForObject("SELECT COUNT(*) FROM knowledge_question", Integer.class));
    }

    @Test
    void failureAfterUnlinkRollsBackTheReferenceAndFileDeletion() {
        seedQuestions(); db.addInterceptor(new FailQuestionMutation(".unlinkFileChunks"));
        assertThrows(RuntimeException.class, () -> files.deleteByFileId("kb", "file-1"));
        assertEquals(101L, db.jdbc().queryForObject("SELECT chunk_id FROM knowledge_question WHERE id=1", Long.class));
        assertEquals(2, db.jdbc().queryForObject("SELECT COUNT(*) FROM knowledge_file_info", Integer.class));
        assertEquals(2, db.jdbc().queryForObject("SELECT COUNT(*) FROM knowledge_chunk", Integer.class));
    }

    @Test
    void fileDeletionPreservesCuratedTextCountsAndOtherKnowledgeBases() {
        seedQuestions(); files.deleteByFileId("kb", "file-1");
        assertNull(db.jdbc().queryForObject("SELECT chunk_id FROM knowledge_question WHERE id=1", Long.class));
        assertEquals("原问题", db.jdbc().queryForObject("SELECT question FROM knowledge_question WHERE id=1", String.class));
        assertEquals(17, db.jdbc().queryForObject("SELECT hit_count FROM knowledge_question WHERE id=1", Integer.class));
        assertEquals(201L, db.jdbc().queryForObject("SELECT chunk_id FROM knowledge_question WHERE id=3", Long.class));
        assertEquals(2, service.listQuestions("kb", null).size()); assertTrue(service.listQuestions("kb", 101L).isEmpty());
    }

    @Test
    void failureAfterQuestionRemovalRestoresTheEntireKnowledgeBase() {
        seedQuestions(); db.addInterceptor(new FailQuestionMutation(".delete"));
        assertThrows(RuntimeException.class, () -> bases.deleteByCode("kb"));
        assertEquals(2, db.jdbc().queryForObject("SELECT COUNT(*) FROM knowledge_base", Integer.class));
        assertEquals(2, db.jdbc().queryForObject("SELECT COUNT(*) FROM knowledge_file_info", Integer.class));
        assertEquals(2, db.jdbc().queryForObject("SELECT COUNT(*) FROM knowledge_chunk", Integer.class));
        assertEquals(3, db.jdbc().queryForObject("SELECT COUNT(*) FROM knowledge_question", Integer.class));
        assertEquals(101L, db.jdbc().queryForObject("SELECT chunk_id FROM knowledge_question WHERE id=1", Long.class));
    }

    @Test
    void retirementHelpersRequireTheOwningMetadataTransaction() {
        seedQuestions();
        assertThrows(IllegalStateException.class, () -> questions.unlinkFileChunks(7L, "file-1"));
        assertThrows(IllegalStateException.class, () -> questions.deleteAllInKnowledgeBase(7L));
        assertEquals(3, db.jdbc().queryForObject("SELECT COUNT(*) FROM knowledge_question", Integer.class));
    }

    @Test
    void aLaterMetadataFailureRestoresTheDetachedQuestionReference() {
        seedQuestions();
        db.jdbc().update("UPDATE knowledge_file_info SET import_job_id='same-job' WHERE file_id='file-1'");
        db.jdbc().execute("ALTER TABLE knowledge_file_info ADD CONSTRAINT reject_fixture_file CHECK(file_name <> '拒绝')");
        var metadata = new MetadataPersistStep(db.mapper(KnowledgeBaseRepository.class), db.mapper(FileInfoRepository.class),
                db.mapper(ChunkRepository.class), new ObjectMapper(), mock(DocumentImportPublicationGuard.class), mock(KnowledgeTagService.class), questions);
        var context = new PipelineContext(); context.setKnowledgeBaseId(7L); context.setKnowledgeBaseCode("kb");
        context.setVectorCollectionName("physical"); context.setFileId("file-1"); context.setFileName("拒绝");
        context.setImportJobId("same-job"); context.setChunks(List.of("替换片段")); context.setVectorIds(List.of("new-vector"));
        assertThrows(RuntimeException.class, () -> new TransactionTemplate(manager).executeWithoutResult(status -> metadata.process(context)));
        assertEquals(101L, db.jdbc().queryForObject("SELECT chunk_id FROM knowledge_question WHERE id=1", Long.class));
        assertEquals("原片段", db.jdbc().queryForObject("SELECT content FROM knowledge_chunk WHERE id=101", String.class));
        assertEquals("原文件", db.jdbc().queryForObject("SELECT file_name FROM knowledge_file_info WHERE id=11", String.class));
    }

    private KnowledgeQuestionRequest request(Long chunk) {
        var request = new KnowledgeQuestionRequest(); request.setChunkId(chunk); request.setQuestion("验证问题"); return request;
    }

    private void seedQuestions() {
        db.jdbc().update("INSERT INTO knowledge_question(id,knowledge_base_id,chunk_id,question,hit_count) VALUES "
                + "(1,7,101,'原问题',17),(2,7,NULL,'知识库问题',3),(3,8,201,'其他知识库问题',5)");
    }

    private void raceRetirement(Runnable retirement) throws Exception {
        var hold = new HoldQuestionInsert(); db.addInterceptor(hold);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var creator = executor.submit(() -> new TransactionTemplate(manager).execute(status -> {
                var request = new KnowledgeQuestionRequest(); request.setQuestion("并发问题"); request.setChunkId(101L);
                return service.createQuestion("kb", request);
            }));
            assertTrue(hold.reached.await(3, TimeUnit.SECONDS), "Question must resolve its owner and target before the pause");
            var deleting = executor.submit(retirement);
            boolean waited = false;
            try { deleting.get(1500, TimeUnit.MILLISECONDS); }
            catch (TimeoutException expectedWhenSerialized) { waited = true; }
            hold.resume.countDown(); creator.get(5, TimeUnit.SECONDS); deleting.get(5, TimeUnit.SECONDS);
            System.out.println("QUESTION_RETIREMENT_INTERLEAVING deletion_waited=" + waited);
        } finally {
            hold.resume.countDown(); executor.shutdownNow(); assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    @Intercepts(@Signature(type=Executor.class, method="update", args={MappedStatement.class,Object.class}))
    static final class HoldQuestionInsert implements Interceptor {
        final CountDownLatch reached = new CountDownLatch(1), resume = new CountDownLatch(1);
        @Override public Object intercept(Invocation invocation) throws Throwable {
            var statement = (MappedStatement) invocation.getArgs()[0];
            if (statement.getId().equals(KnowledgeQuestionRepository.class.getName() + ".insert")) {
                reached.countDown();
                if (!resume.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Controlled question pause timed out");
            }
            return invocation.proceed();
        }
    }

    @Intercepts(@Signature(type=Executor.class, method="update", args={MappedStatement.class,Object.class}))
    static final class FailQuestionMutation implements Interceptor {
        private final String operation; private boolean failed;
        FailQuestionMutation(String operation) { this.operation = operation; }
        @Override public Object intercept(Invocation invocation) throws Throwable {
            var statement = (MappedStatement) invocation.getArgs()[0];
            Object result = invocation.proceed();
            if (!failed && statement.getId().equals(KnowledgeQuestionRepository.class.getName() + operation)) {
                failed = true; throw new IllegalStateException("controlled failure after real question mutation");
            }
            return result;
        }
    }
}
