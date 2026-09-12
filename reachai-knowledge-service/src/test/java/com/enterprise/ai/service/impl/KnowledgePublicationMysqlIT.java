package com.enterprise.ai.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.enterprise.ai.pipeline.PipelineContext;
import com.enterprise.ai.pipeline.document.job.DocumentImportPublicationGuard;
import com.enterprise.ai.pipeline.document.job.DocumentIndexExecutionStore;
import com.enterprise.ai.pipeline.document.job.DocumentIndexVectorManifest;
import com.enterprise.ai.repository.DocumentImportJobRepository;
import com.enterprise.ai.repository.DocumentIndexExecutionRepository;
import org.mybatis.spring.SqlSessionTemplate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfSystemProperty(named = "reachai.mysql.publicationVerification", matches = "true")
class KnowledgePublicationMysqlIT {
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"register", "publish"})
    void leaseIsRecheckedAfterWaitingForJobLock(String mode) throws Exception {
        try (var source = new KnowledgePublicationMysqlDatabase(5)) {
            var jdbc = new JdbcTemplate(source);
            jdbc.update("INSERT INTO knowledge_base(id,name,code,vector_collection_name) VALUES (7,'lease target','kb','kb')");
            jdbc.update("INSERT INTO knowledge_document_import_job(job_id,file_id,knowledge_base_id,knowledge_base_code,file_name,file_type,source_object_key,provider_type,status,stage,lease_owner,lease_until,vector_collection_name) VALUES ('job','file',7,'kb','a.pdf','pdf','source','DOCLING','INDEXING','INDEXING','current','2099-01-01 00:00:00','kb')");
            var config = new MybatisConfiguration(); config.setMapUnderscoreToCamelCase(true);
            config.addMapper(DocumentImportJobRepository.class); config.addMapper(DocumentIndexExecutionRepository.class);
            var observer = new LockReadObserver(); config.addInterceptor(observer);
            var factory = new MybatisSqlSessionFactoryBean(); factory.setDataSource(source); factory.setConfiguration(config);
            var session = new SqlSessionTemplate(factory.getObject());
            var manager = new DataSourceTransactionManager(source);
            var store = KnowledgeIndexTestSupport.executions(session, manager);
            var context = new PipelineContext(); context.setImportJobId("job"); context.setImportLeaseOwner("current");
            context.setKnowledgeBaseCode("kb"); context.setKnowledgeBaseId(7L); context.setVectorCollectionName("kb"); context.setFileId("file"); context.setChunks(java.util.List.of("正文"));
            var manifest = DocumentIndexVectorManifest.forExecution("file", "current", 1);
            context.setVectorIds(manifest.batch(0, 1));
            if (mode.equals("publish")) { assertTrue(store.register(context, manifest)); store.acknowledge("current"); }
            var executor = java.util.concurrent.Executors.newSingleThreadExecutor();
            var pending = new java.util.concurrent.atomic.AtomicReference<java.util.concurrent.Future<?>>();
            try {
                var holder = new TransactionTemplate(manager);
                holder.executeWithoutResult(status -> {
                    jdbc.queryForObject("SELECT id FROM knowledge_document_import_job WHERE job_id='job' FOR UPDATE", Long.class);
                    // Start the waiting statement while the lease is valid, then expire it under the held lock.
                    observer.enabled = true;
                    pending.set(executor.submit(() -> {
                        if (mode.equals("register")) return store.register(context, manifest);
                        new TransactionTemplate(manager).executeWithoutResult(s -> store.lockForPublication(context, 7L));
                        return true;
                    }));
                    try {
                        assertTrue(observer.started.await(5, java.util.concurrent.TimeUnit.SECONDS));
                        Thread.sleep(1100);
                        assertFalse(pending.get().isDone(), "The indexing operation must still be waiting for the held job lock");
                        jdbc.update("UPDATE knowledge_document_import_job SET lease_until=CURRENT_TIMESTAMP WHERE job_id='job'");
                        Thread.sleep(200); // The waiting statement's timestamp predates the committed expiry.
                    } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
                });
                var failure = assertThrows(java.util.concurrent.ExecutionException.class,
                        () -> pending.get().get(10, java.util.concurrent.TimeUnit.SECONDS));
                assertInstanceOf(com.enterprise.ai.pipeline.PipelineException.class, failure.getCause());
                assertEquals(mode.equals("register") ? 0 : 1, jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_document_index_execution", Integer.class));
                System.out.println("MYSQL_LEASE_AFTER_LOCK_VERIFIED mode=" + mode);
            } finally { executor.shutdownNow(); }
        }
    }

    @org.apache.ibatis.plugin.Intercepts(@org.apache.ibatis.plugin.Signature(
            type = org.apache.ibatis.executor.statement.StatementHandler.class, method = "query",
            args = {java.sql.Statement.class, org.apache.ibatis.session.ResultHandler.class}))
    static class LockReadObserver implements org.apache.ibatis.plugin.Interceptor {
        final java.util.concurrent.CountDownLatch started = new java.util.concurrent.CountDownLatch(1);
        volatile boolean enabled;
        public Object intercept(org.apache.ibatis.plugin.Invocation invocation) throws Throwable {
            if (enabled) started.countDown();
            return invocation.proceed();
        }
    }

    @Test
    void completionRollsBackAndPublishesOnlyAfterCommitOnMysql() throws Exception {
        try (var source = new KnowledgePublicationMysqlDatabase()) {
            var jdbc = new JdbcTemplate(source);
            jdbc.update("INSERT INTO knowledge_base(id,name,code,vector_collection_name) VALUES (7,'测试知识库','kb','kb')");
            jdbc.update("INSERT INTO knowledge_document_import_job(job_id,file_id,knowledge_base_id,knowledge_base_code,file_name,file_type,source_object_key,provider_type,status,stage,lease_owner,lease_until,vector_collection_name) VALUES ('job','file',7,'kb','a.pdf','pdf','source','DOCLING','INDEXING','INDEXING','current','2099-01-01 00:00:00','kb')");
            var configuration = new MybatisConfiguration();
            configuration.setMapUnderscoreToCamelCase(true);
            configuration.addMapper(DocumentImportJobRepository.class);
            configuration.addMapper(DocumentIndexExecutionRepository.class);
            configuration.addMapper(com.enterprise.ai.repository.KnowledgeBaseRepository.class);
            configuration.addMapper(com.enterprise.ai.repository.FileInfoRepository.class);
            configuration.addMapper(com.enterprise.ai.repository.ChunkRepository.class);
            var factory = new MybatisSqlSessionFactoryBean();
            factory.setDataSource(source); factory.setConfiguration(configuration);
            var session = new SqlSessionTemplate(factory.getObject());
            var jobs = session.getMapper(DocumentImportJobRepository.class);
            var transactionManager = new DataSourceTransactionManager(source);
            var executions = KnowledgeIndexTestSupport.executions(session, transactionManager);
            var guard = new DocumentImportPublicationGuard(jobs, executions, org.mockito.Mockito.mock(com.enterprise.ai.service.impl.KnowledgeFileDeletionService.class));
            var step = new com.enterprise.ai.pipeline.step.MetadataPersistStep(
                    session.getMapper(com.enterprise.ai.repository.KnowledgeBaseRepository.class),
                    session.getMapper(com.enterprise.ai.repository.FileInfoRepository.class),
                    session.getMapper(com.enterprise.ai.repository.ChunkRepository.class), new com.fasterxml.jackson.databind.ObjectMapper(), guard, org.mockito.Mockito.mock(com.enterprise.ai.service.impl.KnowledgeTagService.class), org.mockito.Mockito.mock(com.enterprise.ai.service.impl.KnowledgeQuestionService.class));
            var context = new PipelineContext();
            context.setFileId("file"); context.setImportJobId("job"); context.setImportLeaseOwner("current");
            context.setKnowledgeBaseCode("kb"); context.setKnowledgeBaseId(7L); context.setVectorCollectionName("kb"); context.setFileName("测试文档.pdf");
            context.setChunks(java.util.List.of("已发布的中文正文"));
            var manifest = DocumentIndexVectorManifest.forExecution("file", "current", 1);
            context.setVectorIds(manifest.batch(0, 1));
            assertTrue(executions.register(context, manifest));
            executions.acknowledge("current");
            var tx = new TransactionTemplate(transactionManager);
            tx.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_REPEATABLE_READ);
            tx.executeWithoutResult(status -> {
                step.process(context);
                assertFalse(context.isImportPublished());
                status.setRollbackOnly();
            });
            assertFalse(context.isImportPublished());
            assertEquals("INDEXING", jdbc.queryForObject("SELECT status FROM knowledge_document_import_job", String.class));
            assertEquals("REGISTERED", jdbc.queryForObject("SELECT state FROM knowledge_document_index_execution", String.class));
            assertEquals(1, jdbc.queryForObject("SELECT write_acknowledged FROM knowledge_document_index_execution", Integer.class));
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_file_info", Integer.class));
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_chunk", Integer.class));
            var competitor = java.util.concurrent.Executors.newSingleThreadExecutor();
            try {
                tx.executeWithoutResult(status -> {
                    guard.lockOwnedExecution(context, 7L);
                    var failureWrite = competitor.submit(() -> jobs.failIndexing("job", "current", "competing failure"));
                    var failure = assertThrows(java.util.concurrent.ExecutionException.class,
                            () -> failureWrite.get(10, java.util.concurrent.TimeUnit.SECONDS));
                    Throwable cause = failure;
                    while (cause.getCause() != null) cause = cause.getCause();
                    assertEquals(1205, assertInstanceOf(java.sql.SQLException.class, cause).getErrorCode());
                    step.process(context);
                    assertFalse(context.isImportPublished());
                });
            } finally {
                competitor.shutdownNow();
            }
            assertTrue(context.isImportPublished());
            assertEquals("测试文档.pdf", jdbc.queryForObject("SELECT file_name FROM knowledge_file_info", String.class));
            assertEquals("已发布的中文正文", jdbc.queryForObject("SELECT content FROM knowledge_chunk", String.class));
            assertEquals(manifest.vectorId(0), jdbc.queryForObject("SELECT vector_id FROM knowledge_chunk", String.class));
            assertEquals("COMPLETED", jdbc.queryForObject("SELECT status FROM knowledge_document_import_job", String.class));
            assertEquals("PUBLISHED", jdbc.queryForObject("SELECT state FROM knowledge_document_index_execution", String.class));
            assertEquals(0, jobs.failIndexing("job", "current", "late failure"));
            assertEquals("COMPLETED", jdbc.queryForObject("SELECT status FROM knowledge_document_import_job", String.class));
        }
    }
}
