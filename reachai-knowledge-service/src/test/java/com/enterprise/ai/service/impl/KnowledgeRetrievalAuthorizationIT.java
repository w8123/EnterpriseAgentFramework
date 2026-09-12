package com.enterprise.ai.service.impl;

import com.enterprise.ai.client.ModelServiceClient;
import com.enterprise.ai.domain.dto.DedupRequest;
import com.enterprise.ai.domain.dto.RagRequest;
import com.enterprise.ai.embedding.EmbeddingService;
import com.enterprise.ai.internal.KnowledgeRetrievalInternalController;
import com.enterprise.ai.pipeline.document.artifact.DocumentArtifactStore;
import com.enterprise.ai.pipeline.document.job.DocumentIndexExecutionStore;
import com.enterprise.ai.rag.LlmService;
import com.enterprise.ai.rag.PromptBuilder;
import com.enterprise.ai.rag.impl.RagServiceImpl;
import com.enterprise.ai.repository.*;
import com.enterprise.ai.retrieval.*;
import com.enterprise.ai.security.impl.PermissionServiceImpl;
import com.enterprise.ai.service.KnowledgeService;
import com.enterprise.ai.support.ArtifactLifecycleTestSupport;
import com.enterprise.ai.vector.VectorSearchResult;
import com.enterprise.ai.vector.VectorService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Map;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Actual development MySQL, isolated cloned tables; remote model/vector operations are controlled substitutes. */
@EnabledIfSystemProperty(named = "reachai.mysql.publicationVerification", matches = "true")
class KnowledgeRetrievalAuthorizationIT {
    @Test
    void realFileRetirementAndRegrantCannotAuthorizeAPausedRetrieval() throws Exception {
        try (var source = KnowledgePublicationMysqlDatabase.withTagLifecycle(10)) {
            var context = new Context(source);
            var held = new CountDownLatch(1); var resume = new CountDownLatch(1);
            when(context.embedding.embed(anyString(), anyString())).thenAnswer(inv -> {
                assertFalse(TransactionSynchronizationManager.isActualTransactionActive()); held.countDown();
                if (!resume.await(15, TimeUnit.SECONDS)) throw new IllegalStateException("Controlled retrieval pause timed out");
                return List.of(0.1f);
            });
            when(context.vectors.search(any())).thenReturn(List.of(hit("new-vector")));
            var executor = Executors.newSingleThreadExecutor();
            try {
                var retrieval = executor.submit(() -> context.controller.retrieve(request("vector")));
                assertTrue(held.await(6, TimeUnit.SECONDS), "Original grant snapshot must precede the deletion");
                context.files.deleteByFileId("kb", "file");
                new TransactionTemplate(context.manager).executeWithoutResult(status -> {
                    context.jdbc.update("INSERT INTO knowledge_file_info(id,file_id,knowledge_base_id,file_name,record_generation) VALUES (12,'file',7,'替换文件',REPEAT('b',32))");
                    context.jdbc.update("INSERT INTO knowledge_chunk(id,file_id,knowledge_base_id,chunk_index,content,vector_id,collection_name,enabled) VALUES (102,'file',7,0,'替换文件内容','new-vector','authorization_physical',1)");
                    context.jdbc.update("INSERT INTO knowledge_user_file_permission(id,user_id,file_id,record_generation) VALUES (2,'actor','file',REPEAT('d',32))");
                });
                resume.countDown(); assertTrue(retrieval.get(15, TimeUnit.SECONDS).getData().empty());
                assertEquals("替换文件内容", context.jdbc.queryForObject("SELECT content FROM knowledge_chunk", String.class));
                assertEquals(1, context.permissions.capture("actor").grants().size());
                System.out.println("MYSQL_RETRIEVAL_AUTHORIZATION_RECREATE_VERIFIED old_request_denied=true new_grant_present=true utf8=true");
            } finally {
                resume.countDown(); executor.shutdownNow(); assertTrue(executor.awaitTermination(15, TimeUnit.SECONDS));
            }
        }
    }

    @Test
    void revokeAndRegrantKeepTheSameFileButRequireANewSnapshot() throws Exception {
        try (var source = KnowledgePublicationMysqlDatabase.withTagLifecycle(10)) {
            var context = new Context(source);
            var original = context.permissions.capture("actor");
            new TransactionTemplate(context.manager).executeWithoutResult(status -> {
                context.jdbc.update("DELETE FROM knowledge_user_file_permission WHERE id=1");
                context.jdbc.update("INSERT INTO knowledge_user_file_permission(id,user_id,file_id,record_generation) VALUES (2,'actor','file',REPEAT('d',32))");
            });
            assertTrue(context.permissions.resolveAuthorizedChunks(original, List.of(101L)).isEmpty());
            var current = context.permissions.resolveAuthorizedChunks(context.permissions.capture("actor"), List.of(101L));
            assertEquals(1, current.size()); assertEquals("原文件内容", current.get(0).content());
            System.out.println("MYSQL_RETRIEVAL_AUTHORIZATION_REGRANT_VERIFIED original_denied=true new_snapshot_allowed=true");
        }
    }

    @Test
    void currentGrantReadsEscapeAnOuterRepeatableReadSnapshot() throws Exception {
        try (var source = KnowledgePublicationMysqlDatabase.withTagLifecycle(10)) {
            var context = new Context(source);
            var original = context.permissions.capture("actor");
            var transaction = new TransactionTemplate(context.manager);
            transaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
            var executor = Executors.newSingleThreadExecutor();
            try {
                transaction.executeWithoutResult(status -> {
                    assertEquals(1, context.jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_user_file_permission", Integer.class));
                    try {
                        executor.submit(() -> context.jdbc.update("DELETE FROM knowledge_user_file_permission WHERE id=1")).get(6, TimeUnit.SECONDS);
                    } catch (Exception failure) { throw new IllegalStateException(failure); }
                    assertEquals(1, context.jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_user_file_permission", Integer.class), "Outer RR still has its original snapshot");
                    assertTrue(context.permissions.resolveAuthorizedChunks(original, List.of(101L)).isEmpty());
                    assertTrue(context.permissions.capture("actor").grants().isEmpty());
                    assertTrue(TransactionSynchronizationManager.isActualTransactionActive(), "Caller transaction must be resumed");
                });
                System.out.println("MYSQL_RETRIEVAL_AUTHORIZATION_CURRENT_READ_VERIFIED outer_snapshot_rows=1 current_grants=0");
            } finally { executor.shutdownNow(); assertTrue(executor.awaitTermination(15, TimeUnit.SECONDS)); }
        }
    }

    @Test
    void ragDiscardsGeneratedAnswerAfterARealGrantRevocation() throws Exception {
        try (var source = KnowledgePublicationMysqlDatabase.withTagLifecycle(10)) {
            var context = new Context(source);
            when(context.llm.chat(anyString(), eq("answer"))).thenAnswer(inv -> {
                assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
                context.jdbc.update("DELETE FROM knowledge_user_file_permission WHERE id=1"); return "应丢弃的生成结果";
            });
            var response = context.rag.query(ragRequest());
            assertTrue(response.getReferences().isEmpty()); assertFalse(response.getAnswer().contains("应丢弃"));
            verify(context.llm).chat(anyString(), eq("answer"));
            System.out.println("MYSQL_RETRIEVAL_AUTHORIZATION_RAG_REVOCATION_VERIFIED answer_discarded=true references_empty=true");
        }
    }

    @Test
    void unchangedGrantAllowsAllThreeEntrancesToReadPublishedContent() throws Exception {
        try (var source = KnowledgePublicationMysqlDatabase.withTagLifecycle(10)) {
            var context = new Context(source);
            for (String mode : List.of("vector", "keyword", "hybrid")) {
                var response = context.controller.retrieve(request(mode)).getData();
                assertEquals(1, response.hitCount()); assertEquals("原文件内容", response.hits().get(0).content());
                assertEquals("原文件", response.hits().get(0).title());
            }
            var dedup = new DedupRequest(); dedup.setUserId("actor"); dedup.setText("文件"); dedup.setKnowledgeBaseCodes(List.of("kb"));
            assertEquals("原文件内容", new DedupServiceImpl(context.core).check(dedup).getItems().get(0).getContent());
            var response = context.rag.query(ragRequest());
            assertEquals("正常回答", response.getAnswer()); assertEquals("原文件内容", response.getReferences().get(0).getContent());
            System.out.println("MYSQL_RETRIEVAL_AUTHORIZATION_NORMAL_VERIFIED runtime=true rag=true dedup=true utf8=true");
        }
    }

    private static RagRequest ragRequest() {
        var request = new RagRequest(); request.setUserId("actor"); request.setQuestion("文件"); request.setKnowledgeBaseCodes(List.of("kb")); return request;
    }
    private static KnowledgeRetrievalInternalController.RetrievalRequest request(String mode) {
        return new KnowledgeRetrievalInternalController.RetrievalRequest("文件", List.of("kb"), "actor", 5, 0.1f, mode, false);
    }
    private static VectorSearchResult hit(String id) {
        return VectorSearchResult.builder().id(id).score(0.95f).fields(Map.of("file_id", "forged", "content", "不可信向量正文")).build();
    }

    private static final class Context {
        final JdbcTemplate jdbc;
        final DataSourceTransactionManager manager;
        final PermissionServiceImpl permissions;
        final EmbeddingService embedding = mock(EmbeddingService.class);
        final VectorService vectors = mock(VectorService.class);
        final LlmService llm = mock(LlmService.class);
        final KnowledgeRetrievalCore core;
        final KnowledgeRetrievalInternalController controller;
        final KnowledgeFileDeletionService files;
        final RagServiceImpl rag;

        Context(KnowledgePublicationMysqlDatabase source) throws Exception {
            jdbc = new JdbcTemplate(source); manager = new DataSourceTransactionManager(source);
            SqlSessionTemplate session = ArtifactLifecycleTestSupport.session(source, KnowledgeBaseRepository.class, FileInfoRepository.class,
                    ChunkRepository.class, UserFilePermissionRepository.class, KnowledgeTagRepository.class, KnowledgeQuestionRepository.class,
                    DocumentImportJobRepository.class, DocumentIndexExecutionRepository.class);
            var bases = session.getMapper(KnowledgeBaseRepository.class); var chunks = session.getMapper(ChunkRepository.class);
            var fileRepository = session.getMapper(FileInfoRepository.class); var grantRepository = session.getMapper(UserFilePermissionRepository.class);
            permissions = new PermissionServiceImpl(grantRepository, manager);
            var authorization = new KnowledgeRetrievalAuthorization(permissions);
            core = new KnowledgeRetrievalCore(new DefaultKnowledgeRetrievalEngine(new KnowledgeBaseLookup(bases), fileRepository, chunks,
                    mock(KnowledgeHitLogRepository.class), embedding, mock(ModelServiceClient.class), vectors, authorization), authorization);
            controller = new KnowledgeRetrievalInternalController(core);
            var tags = new KnowledgeTagService(bases, fileRepository, chunks, session.getMapper(KnowledgeTagRepository.class), manager);
            var questions = new KnowledgeQuestionService(bases, chunks, session.getMapper(KnowledgeQuestionRepository.class), manager);
            files = new KnowledgeFileDeletionService(bases, fileRepository, chunks,
                    session.getMapper(DocumentImportJobRepository.class), session.getMapper(DocumentIndexExecutionRepository.class),
                    mock(DocumentIndexExecutionStore.class), mock(DocumentArtifactStore.class), grantRepository, questions, manager, tags);
            var knowledge = mock(KnowledgeService.class); var prompt = mock(PromptBuilder.class);
            when(knowledge.resolveKnowledgeBases(any())).thenAnswer(inv -> List.of(bases.selectById(7L)));
            when(prompt.build(anyString(), anyList())).thenReturn("已授权上下文");
            when(llm.chat(anyString(), eq("answer"))).thenAnswer(inv -> {
                assertFalse(TransactionSynchronizationManager.isActualTransactionActive()); return "正常回答";
            });
            rag = new RagServiceImpl(core, knowledge, llm, prompt);
            when(embedding.embed(anyString(), anyString())).thenAnswer(inv -> {
                assertFalse(TransactionSynchronizationManager.isActualTransactionActive()); return List.of(0.1f);
            });
            when(vectors.search(any())).thenReturn(List.of(hit("old-vector")));
            jdbc.update("INSERT INTO knowledge_base(id,code,name,status,vector_collection_name,embedding_model_instance_id,llm_model_instance_id) "
                    + "VALUES (7,'kb','授权检索',1,'authorization_physical','embedding','answer')");
            jdbc.update("INSERT INTO knowledge_file_info(id,file_id,knowledge_base_id,file_name,record_generation) VALUES (11,'file',7,'原文件',REPEAT('a',32))");
            jdbc.update("INSERT INTO knowledge_chunk(id,file_id,knowledge_base_id,chunk_index,content,vector_id,collection_name,enabled) VALUES (101,'file',7,0,'原文件内容','old-vector','authorization_physical',1)");
            jdbc.update("INSERT INTO knowledge_user_file_permission(id,user_id,file_id,record_generation) VALUES (1,'actor','file',REPEAT('c',32))");
            assertEquals("授权检索", jdbc.queryForObject("SELECT name FROM knowledge_base", String.class));
        }
    }
}
