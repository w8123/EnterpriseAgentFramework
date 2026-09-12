package com.enterprise.ai.service.impl;

import com.enterprise.ai.domain.dto.KnowledgeQuestionRequest;
import com.enterprise.ai.pipeline.PipelineContext;
import com.enterprise.ai.pipeline.document.artifact.DocumentArtifactStore;
import com.enterprise.ai.pipeline.document.job.DocumentImportPublicationGuard;
import com.enterprise.ai.pipeline.document.job.DocumentIndexExecutionStore;
import com.enterprise.ai.pipeline.step.MetadataPersistStep;
import com.enterprise.ai.repository.*;
import com.enterprise.ai.support.ArtifactLifecycleTestSupport;
import com.enterprise.ai.vector.VectorService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.executor.statement.StatementHandler;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.plugin.*;
import org.apache.ibatis.session.ResultHandler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Statement;
import java.util.List;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/** Real MySQL RR transactions; external collection/artifact effects are outside this test's scope. */
@EnabledIfSystemProperty(named = "reachai.mysql.publicationVerification", matches = "true")
class KnowledgeQuestionLifecycleIT {
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void creationThenRetirementCannotLeaveAnObsoleteReference(boolean wholeBase) throws Exception {
        try (var source = KnowledgePublicationMysqlDatabase.withTagLifecycle(10)) {
            var context = new Context(source);
            var ordering = new MutationOrder("creator", KnowledgeQuestionRepository.class.getName() + ".insert", false, "deleter");
            context.session.getConfiguration().addInterceptor(ordering);
            var executor = Executors.newFixedThreadPool(2);
            try {
                var creating = task(executor, "creator", () -> context.questions.create("kb", request(wholeBase ? null : 101L)));
                assertTrue(ordering.held.await(6, TimeUnit.SECONDS), "Creator must hold the owning knowledge-base lock");
                var deleting = task(executor, "deleter", () -> { context.retire(wholeBase); return null; });
                assertTrue(ordering.waiting.await(6, TimeUnit.SECONDS), "Retirement must reach the same knowledge-base lock");
                assertFalse(deleting.isDone());
                ordering.resume.countDown();
                assertEquals("并发问题", creating.get(15, TimeUnit.SECONDS).getQuestion());
                deleting.get(15, TimeUnit.SECONDS);
                context.assertRetired(wholeBase);
                var remaining = context.jdbc.queryForList("SELECT chunk_id,question FROM knowledge_question WHERE knowledge_base_id=7");
                assertEquals(wholeBase ? 0 : 1, remaining.size());
                if (!wholeBase) {
                    assertNull(remaining.get(0).get("chunk_id"));
                    assertEquals("并发问题", remaining.get(0).get("question"));
                }
                System.out.println("MYSQL_QUESTION_LIFECYCLE_ORDER_VERIFIED order=create_delete target=" + (wholeBase ? "BASE" : "FILE"));
            } finally {
                ordering.resume.countDown(); executor.shutdownNow(); assertTrue(executor.awaitTermination(15, TimeUnit.SECONDS));
            }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void retirementThenCreationRejectsTheTargetAfterItsSnapshotRead(boolean wholeBase) throws Exception {
        try (var source = KnowledgePublicationMysqlDatabase.withTagLifecycle(10)) {
            var context = new Context(source);
            String statement = (wholeBase ? KnowledgeBaseRepository.class : FileInfoRepository.class).getName() + ".deleteById";
            var ordering = new MutationOrder("deleter", statement, true, "creator");
            context.session.getConfiguration().addInterceptor(ordering);
            var executor = Executors.newFixedThreadPool(2);
            try {
                var deleting = task(executor, "deleter", () -> { context.retire(wholeBase); return null; });
                assertTrue(ordering.held.await(6, TimeUnit.SECONDS), "The actual deletion must be uncommitted");
                var creating = task(executor, "creator", () -> context.questions.create("kb", request(wholeBase ? null : 101L)));
                assertTrue(ordering.waiting.await(6, TimeUnit.SECONDS), "Creator must reach the owning row lock after its snapshot read");
                assertFalse(creating.isDone());
                ordering.resume.countDown(); deleting.get(15, TimeUnit.SECONDS);
                var failure = assertThrows(ExecutionException.class, () -> creating.get(15, TimeUnit.SECONDS));
                if (wholeBase) assertInstanceOf(IllegalStateException.class, failure.getCause());
                else assertInstanceOf(IllegalArgumentException.class, failure.getCause());
                context.assertRetired(wholeBase);
                assertEquals(0, context.jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_question WHERE knowledge_base_id=7", Integer.class));
                System.out.println("MYSQL_QUESTION_LIFECYCLE_ORDER_VERIFIED order=delete_create target=" + (wholeBase ? "BASE" : "FILE") + " stale_snapshot_rejected=true");
            } finally {
                ordering.resume.countDown(); executor.shutdownNow(); assertTrue(executor.awaitTermination(15, TimeUnit.SECONDS));
            }
        }
    }

    @Test
    void metadataReplacementRollsBackThenDetachesOnlyTheOldReference() throws Exception {
        try (var source = KnowledgePublicationMysqlDatabase.withTagLifecycle(10)) {
            var context = new Context(source);
            var question = context.questions.create("kb", request(101L));
            context.jdbc.update("UPDATE knowledge_question SET hit_count=17 WHERE id=?", question.getId());
            context.jdbc.update("UPDATE knowledge_file_info SET import_job_id='same-job' WHERE file_id='file-1'");
            var metadata = new MetadataPersistStep(context.session.getMapper(KnowledgeBaseRepository.class),
                    context.session.getMapper(FileInfoRepository.class), context.session.getMapper(ChunkRepository.class),
                    new ObjectMapper(), mock(DocumentImportPublicationGuard.class), context.tags, context.questions);
            var pipeline = new PipelineContext(); pipeline.setKnowledgeBaseId(7L); pipeline.setKnowledgeBaseCode("kb");
            pipeline.setVectorCollectionName("question_fixture_physical"); pipeline.setFileId("file-1");
            pipeline.setFileName("替换文件"); pipeline.setImportJobId("same-job");
            pipeline.setChunks(List.of("替换片段")); pipeline.setVectorIds(List.of("replacement-vector"));
            context.session.getConfiguration().addInterceptor(new FailReplacementInsert());
            var transaction = new TransactionTemplate(context.manager);
            assertThrows(RuntimeException.class, () -> transaction.executeWithoutResult(status -> metadata.process(pipeline)));
            assertEquals(101L, context.jdbc.queryForObject("SELECT chunk_id FROM knowledge_question WHERE id=?", Long.class, question.getId()));
            assertEquals("目标文件", context.jdbc.queryForObject("SELECT file_name FROM knowledge_file_info WHERE id=11", String.class));
            assertEquals("目标片段", context.jdbc.queryForObject("SELECT content FROM knowledge_chunk WHERE id=101", String.class));
            transaction.executeWithoutResult(status -> metadata.process(pipeline));
            assertNull(context.jdbc.queryForObject("SELECT chunk_id FROM knowledge_question WHERE id=?", Long.class, question.getId()));
            assertEquals("并发问题", context.jdbc.queryForObject("SELECT question FROM knowledge_question WHERE id=?", String.class, question.getId()));
            assertEquals(17, context.jdbc.queryForObject("SELECT hit_count FROM knowledge_question WHERE id=?", Integer.class, question.getId()));
            assertEquals("替换片段", context.jdbc.queryForObject("SELECT content FROM knowledge_chunk WHERE knowledge_base_id=7", String.class));
            context.assertOtherBase();
            System.out.println("MYSQL_QUESTION_METADATA_REPLACEMENT_VERIFIED rollback=true retry=true utf8=true");
        }
    }

    private static KnowledgeQuestionRequest request(Long chunkId) {
        var request = new KnowledgeQuestionRequest(); request.setChunkId(chunkId); request.setQuestion("并发问题"); return request;
    }

    private static <T> Future<T> task(ExecutorService executor, String name, Callable<T> action) {
        return executor.submit(() -> {
            String previous = Thread.currentThread().getName();
            try { Thread.currentThread().setName(name); return action.call(); }
            finally { Thread.currentThread().setName(previous); }
        });
    }

    private static final class Context {
        final JdbcTemplate jdbc;
        final SqlSessionTemplate session;
        final DataSourceTransactionManager manager;
        final KnowledgeQuestionService questions;
        final KnowledgeTagService tags;
        final KnowledgeFileDeletionService files;
        final KnowledgeBaseLifecycleService bases;
        Context(KnowledgePublicationMysqlDatabase source) throws Exception {
            jdbc = new JdbcTemplate(source);
            session = ArtifactLifecycleTestSupport.session(source, KnowledgeBaseRepository.class, FileInfoRepository.class,
                    ChunkRepository.class, KnowledgeTagRepository.class, KnowledgeQuestionRepository.class,
                    DocumentImportJobRepository.class, DocumentIndexExecutionRepository.class, UserFilePermissionRepository.class);
            manager = new DataSourceTransactionManager(source);
            var baseRepository = session.getMapper(KnowledgeBaseRepository.class);
            var fileRepository = session.getMapper(FileInfoRepository.class);
            var chunkRepository = session.getMapper(ChunkRepository.class);
            var tagRepository = session.getMapper(KnowledgeTagRepository.class);
            questions = new KnowledgeQuestionService(baseRepository, chunkRepository, session.getMapper(KnowledgeQuestionRepository.class), manager);
            tags = new KnowledgeTagService(baseRepository, fileRepository, chunkRepository, tagRepository, manager);
            files = new KnowledgeFileDeletionService(baseRepository, fileRepository, chunkRepository,
                    session.getMapper(DocumentImportJobRepository.class), session.getMapper(DocumentIndexExecutionRepository.class),
                    mock(DocumentIndexExecutionStore.class), mock(DocumentArtifactStore.class),
                    session.getMapper(UserFilePermissionRepository.class), questions, manager, tags);
            bases = new KnowledgeBaseLifecycleService(baseRepository, tagRepository, questions, files,
                    mock(KnowledgeCollectionLifecycleStore.class), mock(VectorService.class), manager);
            jdbc.update("INSERT INTO knowledge_base(id,code,name,vector_collection_name) VALUES "
                    + "(7,'kb','问题并发验证','question_fixture_physical'),(8,'other','其他知识库','other_physical')");
            jdbc.update("INSERT INTO knowledge_file_info(id,file_id,knowledge_base_id,file_name) VALUES (11,'file-1',7,'目标文件'),(12,'keep',8,'保留文件')");
            jdbc.update("INSERT INTO knowledge_chunk(id,file_id,knowledge_base_id,chunk_index,content) VALUES (101,'file-1',7,0,'目标片段'),(201,'keep',8,0,'保留片段')");
            jdbc.update("INSERT INTO knowledge_question(id,knowledge_base_id,chunk_id,question,hit_count) VALUES (900,8,201,'其他问题',5)");
            assertEquals("问题并发验证", jdbc.queryForObject("SELECT name FROM knowledge_base WHERE id=7", String.class));
        }
        void retire(boolean wholeBase) {
            if (wholeBase) bases.deleteByCode("kb"); else files.deleteByFileId("kb", "file-1");
        }
        void assertRetired(boolean wholeBase) {
            assertEquals(wholeBase ? 0 : 1, jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_base WHERE id=7", Integer.class));
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_file_info WHERE knowledge_base_id=7", Integer.class));
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_chunk WHERE knowledge_base_id=7", Integer.class));
            assertOtherBase();
        }
        void assertOtherBase() {
            assertEquals("保留文件", jdbc.queryForObject("SELECT file_name FROM knowledge_file_info WHERE id=12", String.class));
            assertEquals("保留片段", jdbc.queryForObject("SELECT content FROM knowledge_chunk WHERE id=201", String.class));
            assertEquals("其他问题", jdbc.queryForObject("SELECT question FROM knowledge_question WHERE id=900", String.class));
            assertEquals(201L, jdbc.queryForObject("SELECT chunk_id FROM knowledge_question WHERE id=900", Long.class));
        }
    }

    @Intercepts({@Signature(type = Executor.class, method = "update", args = {MappedStatement.class, Object.class}),
            @Signature(type = StatementHandler.class, method = "query", args = {Statement.class, ResultHandler.class})})
    static final class MutationOrder implements Interceptor {
        final CountDownLatch held = new CountDownLatch(1), waiting = new CountDownLatch(1), resume = new CountDownLatch(1);
        private final String heldThread, statement, waitingThread;
        private final boolean after;
        MutationOrder(String heldThread, String statement, boolean after, String waitingThread) {
            this.heldThread = heldThread; this.statement = statement; this.after = after; this.waitingThread = waitingThread;
        }
        @Override public Object intercept(Invocation invocation) throws Throwable {
            String thread = Thread.currentThread().getName();
            if (invocation.getTarget() instanceof StatementHandler handler) {
                if (thread.equals(waitingThread) && handler.getBoundSql().getSql().contains("SELECT * FROM knowledge_base WHERE id=")) waiting.countDown();
                return invocation.proceed();
            }
            var mapped = (MappedStatement) invocation.getArgs()[0];
            if (!thread.equals(heldThread) || !mapped.getId().equals(statement)) return invocation.proceed();
            Object result = after ? invocation.proceed() : null;
            held.countDown();
            if (!resume.await(8, TimeUnit.SECONDS)) throw new IllegalStateException("Controlled transaction ordering timed out");
            return after ? result : invocation.proceed();
        }
    }

    @Intercepts(@Signature(type = Executor.class, method = "update", args = {MappedStatement.class, Object.class}))
    static final class FailReplacementInsert implements Interceptor {
        private boolean failed;
        @Override public Object intercept(Invocation invocation) throws Throwable {
            var statement = (MappedStatement) invocation.getArgs()[0];
            Object result = invocation.proceed();
            if (!failed && statement.getId().equals(FileInfoRepository.class.getName() + ".insert")) {
                failed = true; throw new IllegalStateException("Controlled failure after actual replacement insert");
            }
            return result;
        }
    }
}
