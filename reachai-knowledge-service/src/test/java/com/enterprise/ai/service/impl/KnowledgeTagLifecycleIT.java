package com.enterprise.ai.service.impl;

import com.enterprise.ai.domain.dto.KnowledgeTagBatchRequest;
import com.enterprise.ai.domain.dto.KnowledgeTagRequest;
import com.enterprise.ai.pipeline.document.artifact.DocumentArtifactStore;
import com.enterprise.ai.pipeline.document.job.DocumentIndexExecutionStore;
import com.enterprise.ai.repository.*;
import com.enterprise.ai.support.ArtifactLifecycleTestSupport;
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

import java.sql.Statement;
import java.util.List;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real MySQL REPEATABLE READ transactions and controlled, observed row-lock interleavings. */
@EnabledIfSystemProperty(named = "reachai.mysql.publicationVerification", matches = "true")
class KnowledgeTagLifecycleIT {

    @ParameterizedTest
    @ValueSource(strings = {"FILE", "CHUNK"})
    void creationThenDeletionCannotLeaveAnOrphan(String type) throws Exception {
        try (var source = KnowledgePublicationMysqlDatabase.withTagLifecycle(10)) {
            var context = new Context(source);
            var ordering = new MutationOrder("creator", KnowledgeTagRepository.class.getName() + ".insert", false, "deleter");
            context.session.getConfiguration().addInterceptor(ordering);
            var executor = Executors.newFixedThreadPool(2);
            try {
                var creating = task(executor, "creator", () -> context.tags.create("kb", request(type)));
                assertTrue(ordering.held.await(6, TimeUnit.SECONDS), "Creator must hold the knowledge-base transaction");
                var deleting = task(executor, "deleter", () -> { context.deletion.deleteByFileId("kb", "file-1"); return null; });
                assertTrue(ordering.waiting.await(6, TimeUnit.SECONDS), "Delete must reach the same knowledge-base lock");
                assertFalse(deleting.isDone());
                ordering.resume.countDown();
                assertEquals("合同", creating.get(15, TimeUnit.SECONDS).getTagValue());
                deleting.get(15, TimeUnit.SECONDS);
                context.assertRetired();
                System.out.println("MYSQL_TAG_LIFECYCLE_ORDER_VERIFIED order=create_delete type=" + type);
            } finally {
                ordering.resume.countDown(); executor.shutdownNow(); assertTrue(executor.awaitTermination(15, TimeUnit.SECONDS));
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"FILE", "CHUNK"})
    void deletionThenCreationRechecksTargetsAfterTheLockWait(String type) throws Exception {
        try (var source = KnowledgePublicationMysqlDatabase.withTagLifecycle(10)) {
            var context = new Context(source);
            var ordering = new MutationOrder("deleter", FileInfoRepository.class.getName() + ".deleteById", true, "creator");
            context.session.getConfiguration().addInterceptor(ordering);
            var executor = Executors.newFixedThreadPool(2);
            try {
                var deleting = task(executor, "deleter", () -> { context.deletion.deleteByFileId("kb", "file-1"); return null; });
                assertTrue(ordering.held.await(6, TimeUnit.SECONDS), "Deletion must be applied but uncommitted");
                var creating = task(executor, "creator", () -> context.tags.create("kb", request(type)));
                assertTrue(ordering.waiting.await(6, TimeUnit.SECONDS), "Creator must reach the knowledge-base lock after its snapshot read");
                assertFalse(creating.isDone());
                ordering.resume.countDown(); deleting.get(15, TimeUnit.SECONDS);
                var failure = assertThrows(ExecutionException.class, () -> creating.get(15, TimeUnit.SECONDS));
                assertInstanceOf(IllegalArgumentException.class, failure.getCause());
                context.assertRetired();
                System.out.println("MYSQL_TAG_LIFECYCLE_ORDER_VERIFIED order=delete_create type=" + type + " stale_snapshot_rejected=true");
            } finally {
                ordering.resume.countDown(); executor.shutdownNow(); assertTrue(executor.awaitTermination(15, TimeUnit.SECONDS));
            }
        }
    }

    @Test
    void concurrentBatchesDoNotDuplicateAnAssociationAfterTheirSnapshotReads() throws Exception {
        try (var source = KnowledgePublicationMysqlDatabase.withTagLifecycle(10)) {
            var context = new Context(source);
            var ordering = new MutationOrder("first", KnowledgeTagRepository.class.getName() + ".insert", true, "second");
            context.session.getConfiguration().addInterceptor(ordering);
            var executor = Executors.newFixedThreadPool(2);
            var request = new KnowledgeTagBatchRequest(); request.setTargetType("FILE");
            request.setTargetIds(List.of("file-1")); request.setTagKey("主题"); request.setTagValue("合同");
            try {
                var first = task(executor, "first", () -> context.tags.createBatch("kb", request));
                assertTrue(ordering.held.await(6, TimeUnit.SECONDS));
                var second = task(executor, "second", () -> context.tags.createBatch("kb", request));
                assertTrue(ordering.waiting.await(6, TimeUnit.SECONDS)); assertFalse(second.isDone());
                ordering.resume.countDown();
                assertEquals(1, first.get(15, TimeUnit.SECONDS).size()); assertTrue(second.get(15, TimeUnit.SECONDS).isEmpty());
                assertEquals(1, context.jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_tag", Integer.class));
                assertEquals("合同", context.jdbc.queryForObject("SELECT tag_value FROM knowledge_tag", String.class));
                System.out.println("MYSQL_TAG_LIFECYCLE_DUPLICATE_RACE_VERIFIED associations=1 utf8=true");
            } finally {
                ordering.resume.countDown(); executor.shutdownNow(); assertTrue(executor.awaitTermination(15, TimeUnit.SECONDS));
            }
        }
    }

    private static KnowledgeTagRequest request(String type) {
        var request = new KnowledgeTagRequest(); request.setTargetType(type);
        request.setTargetId("FILE".equals(type) ? "file-1" : "00101");
        request.setTagKey("主题"); request.setTagValue("合同"); return request;
    }

    private static <T> Future<T> task(ExecutorService executor, String name, Callable<T> action) {
        return executor.submit(() -> {
            String original = Thread.currentThread().getName();
            try { Thread.currentThread().setName(name); return action.call(); }
            finally { Thread.currentThread().setName(original); }
        });
    }

    private static final class Context {
        final JdbcTemplate jdbc;
        final SqlSessionTemplate session;
        final KnowledgeTagService tags;
        final KnowledgeFileDeletionService deletion;
        Context(KnowledgePublicationMysqlDatabase source) throws Exception {
            jdbc = new JdbcTemplate(source);
            session = ArtifactLifecycleTestSupport.session(source, KnowledgeBaseRepository.class, FileInfoRepository.class,
                    ChunkRepository.class, KnowledgeTagRepository.class, DocumentImportJobRepository.class,
                    DocumentIndexExecutionRepository.class, UserFilePermissionRepository.class, KnowledgeQuestionRepository.class);
            var manager = new DataSourceTransactionManager(source);
            tags = new KnowledgeTagService(session.getMapper(KnowledgeBaseRepository.class), session.getMapper(FileInfoRepository.class),
                    session.getMapper(ChunkRepository.class), session.getMapper(KnowledgeTagRepository.class), manager);
            deletion = new KnowledgeFileDeletionService(session.getMapper(KnowledgeBaseRepository.class),
                    session.getMapper(FileInfoRepository.class), session.getMapper(ChunkRepository.class),
                    session.getMapper(DocumentImportJobRepository.class), session.getMapper(DocumentIndexExecutionRepository.class),
                    mock(DocumentIndexExecutionStore.class), mock(DocumentArtifactStore.class),
                    session.getMapper(UserFilePermissionRepository.class), new com.enterprise.ai.service.impl.KnowledgeQuestionService(session.getMapper(KnowledgeBaseRepository.class), session.getMapper(ChunkRepository.class), session.getMapper(KnowledgeQuestionRepository.class), manager), manager, tags);
            jdbc.update("INSERT INTO knowledge_base(id,code,name,vector_collection_name) VALUES (7,'kb','标签并发验证','tag_fixture_physical')");
            jdbc.update("INSERT INTO knowledge_file_info(id,file_id,knowledge_base_id,file_name) VALUES (11,'file-1',7,'目标文件'),(12,'keep',7,'保留文件')");
            jdbc.update("INSERT INTO knowledge_chunk(id,file_id,knowledge_base_id,chunk_index,content) VALUES (101,'file-1',7,0,'目标片段')");
            assertEquals("标签并发验证", jdbc.queryForObject("SELECT name FROM knowledge_base", String.class));
        }

        void assertRetired() {
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_tag", Integer.class));
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_chunk", Integer.class));
            assertEquals("keep", jdbc.queryForObject("SELECT file_id FROM knowledge_file_info", String.class));
            assertEquals("保留文件", jdbc.queryForObject("SELECT file_name FROM knowledge_file_info", String.class));
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
}
