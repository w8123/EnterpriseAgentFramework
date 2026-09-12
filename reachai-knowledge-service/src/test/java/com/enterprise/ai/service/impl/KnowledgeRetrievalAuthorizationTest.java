package com.enterprise.ai.service.impl;

import com.enterprise.ai.client.ModelServiceClient;
import com.enterprise.ai.common.dto.ApiResult;
import com.enterprise.ai.domain.dto.DedupRequest;
import com.enterprise.ai.domain.dto.RagRequest;
import com.enterprise.ai.embedding.EmbeddingService;
import com.enterprise.ai.internal.KnowledgeRetrievalInternalController;
import com.enterprise.ai.rag.LlmService;
import com.enterprise.ai.rag.PromptBuilder;
import com.enterprise.ai.rag.impl.RagServiceImpl;
import com.enterprise.ai.repository.*;
import com.enterprise.ai.retrieval.DefaultKnowledgeRetrievalEngine;
import com.enterprise.ai.retrieval.KnowledgeRetrievalCore;
import com.enterprise.ai.security.impl.PermissionServiceImpl;
import com.enterprise.ai.service.KnowledgeService;
import com.enterprise.ai.support.KnowledgeQueryTestDatabase;
import com.enterprise.ai.vector.VectorSearchResult;
import com.enterprise.ai.vector.VectorService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class KnowledgeRetrievalAuthorizationTest {
    private KnowledgeQueryTestDatabase db;
    private EmbeddingService embedding;
    private VectorService vectors;
    private ModelServiceClient model;
    private PermissionServiceImpl permissions;
    private KnowledgeService knowledge;
    private KnowledgeRetrievalCore core;
    private KnowledgeRetrievalInternalController controller;

    @BeforeEach
    void open() throws Exception {
        db = new KnowledgeQueryTestDatabase(List.of("knowledge_base", "knowledge_file_info", "knowledge_chunk", "knowledge_user_file_permission"),
                KnowledgeBaseRepository.class, FileInfoRepository.class, ChunkRepository.class, UserFilePermissionRepository.class);
        db.jdbc().update("INSERT INTO knowledge_base(id,code,name,status,vector_collection_name,embedding_model_instance_id,rerank_model_instance_id,llm_model_instance_id,rerank_enabled) "
                + "VALUES (7,'kb','授权检索',1,'physical','embedding','rerank','answer',1)");
        db.jdbc().update("INSERT INTO knowledge_file_info(id,file_id,knowledge_base_id,file_name,record_generation) VALUES (11,'file',7,'原文件',REPEAT('a',32))");
        db.jdbc().update("INSERT INTO knowledge_chunk(id,file_id,knowledge_base_id,chunk_index,content,vector_id,enabled) VALUES (101,'file',7,0,'原文件内容','old-vector',1)");
        db.jdbc().update("INSERT INTO knowledge_user_file_permission(id,user_id,file_id,record_generation) VALUES (1,'actor','file',REPEAT('c',32))");
        embedding = mock(EmbeddingService.class); vectors = mock(VectorService.class); model = mock(ModelServiceClient.class);
        permissions = new PermissionServiceImpl(db.mapper(UserFilePermissionRepository.class), new org.springframework.jdbc.datasource.DataSourceTransactionManager(db.jdbc().getDataSource()));
        knowledge = mock(KnowledgeService.class);
        when(knowledge.resolveKnowledgeBases(any())).thenAnswer(inv -> List.of(db.mapper(KnowledgeBaseRepository.class).selectById(7L)));
        when(embedding.embed(anyString(), anyString())).thenReturn(List.of(0.1f));
        when(vectors.search(any())).thenReturn(List.of(hit("old-vector", "原文件内容")));
        var authorization = new com.enterprise.ai.retrieval.KnowledgeRetrievalAuthorization(permissions);
        core = new KnowledgeRetrievalCore(new DefaultKnowledgeRetrievalEngine(new KnowledgeBaseLookup(db.mapper(KnowledgeBaseRepository.class)),
                db.mapper(FileInfoRepository.class), db.mapper(ChunkRepository.class), mock(KnowledgeHitLogRepository.class), embedding, model, vectors, authorization), authorization);
        controller = new KnowledgeRetrievalInternalController(core);
    }

    @AfterEach void close() { if (db != null) db.close(); }

    @Test
    void runtimeCannotReadTheReplacementBehindAnOldFilePermission() {
        replaceDuringEmbedding();
        assertTrue(controller.retrieve(request(false)).getData().hits().isEmpty(),
                "An old permission snapshot must not authorize a replacement file");
    }

    @Test
    void revocationBeforeRerankStopsExternalContentDisclosure() {
        when(vectors.search(any())).thenAnswer(inv -> {
            db.jdbc().update("DELETE FROM knowledge_user_file_permission WHERE id=1");
            return List.of(hit("old-vector", "原文件内容"));
        });
        controller.retrieve(request(true));
        verifyNoInteractions(model);
    }

    @Test
    void revocationDuringRerankCannotReturnEarlierContent() {
        when(model.rerank(any())).thenAnswer(inv -> {
            db.jdbc().update("DELETE FROM knowledge_user_file_permission WHERE id=1");
            return ApiResult.ok(null);
        });
        assertTrue(controller.retrieve(request(true)).getData().hits().isEmpty(),
                "Content must be revalidated after an external rerank call");
    }

    @Test
    void ragCannotReadTheReplacementBehindAnOldFilePermission() {
        replaceDuringEmbedding();
        var rag = new RagServiceImpl(core, knowledge, mock(LlmService.class), mock(PromptBuilder.class));
        var request = new RagRequest(); request.setUserId("actor"); request.setQuestion("文件"); request.setKnowledgeBaseCodes(List.of("kb"));
        assertTrue(rag.retrieve(request).getReferences().isEmpty(), "RAG must use the same file-generation fence");
    }

    @Test
    void dedupCannotReadTheReplacementBehindAnOldFilePermission() {
        replaceDuringEmbedding();
        var dedup = new DedupServiceImpl(core);
        var request = new DedupRequest(); request.setUserId("actor"); request.setText("文件"); request.setKnowledgeBaseCodes(List.of("kb"));
        assertTrue(dedup.check(request).getItems().isEmpty(), "Dedup must use the same file-generation fence");
    }

    @ParameterizedTest
    @ValueSource(strings = {"vector", "keyword", "hybrid"})
    void authorizedRetrievalUsesPublishedDatabaseContent(String mode) {
        when(vectors.search(any())).thenReturn(List.of(VectorSearchResult.builder().id("old-vector").score(0.95f)
                .fields(Map.of("file_id", "untrusted", "content", "未发布的远端内容")).build()));
        var response = controller.retrieve(new KnowledgeRetrievalInternalController.RetrievalRequest(
                "文件", List.of("kb"), "actor", 5, 0.1f, mode, false)).getData();
        assertEquals(1, response.hitCount()); assertEquals("原文件内容", response.hits().get(0).content());
        assertEquals("原文件", response.hits().get(0).title());
        verifyNoInteractions(model);
    }

    @Test
    void missingActorIsRejectedBeforeAnyExternalCall() {
        for (String actor : new String[]{null, "", "  "}) {
            assertThrows(IllegalArgumentException.class, () -> core.retrieve(com.enterprise.ai.retrieval.KnowledgeRetrievalCoreRequest.builder()
                    .query("文件").userId(actor).knowledgeBaseCodes(List.of("kb")).build()));
        }
        verifyNoInteractions(embedding, vectors, model);
    }

    @Test
    void aUserWithoutGrantsNeverInvokesExternalRetrieval() {
        db.jdbc().update("DELETE FROM knowledge_user_file_permission");
        assertTrue(controller.retrieve(request(true)).getData().empty());
        verifyNoInteractions(embedding, vectors, model);
    }

    @Test
    void anUngrantableVectorCandidateCannotReachRerank() {
        db.jdbc().update("INSERT INTO knowledge_file_info(id,file_id,knowledge_base_id,file_name) VALUES (13,'secret',7,'受限文件')");
        db.jdbc().update("INSERT INTO knowledge_chunk(id,file_id,knowledge_base_id,chunk_index,content,vector_id,enabled) VALUES (103,'secret',7,0,'受限文件正文','secret-vector',1)");
        when(vectors.search(any())).thenReturn(List.of(hit("secret-vector", "受限文件正文")));
        assertTrue(controller.retrieve(request(true)).getData().empty());
        verifyNoInteractions(model);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void replacementCannotUseAPreservedOrNewGrant(boolean newGrant) {
        when(embedding.embed(anyString(), anyString())).thenAnswer(inv -> {
            replaceFile(newGrant);
            if (newGrant) db.jdbc().update("INSERT INTO knowledge_user_file_permission(id,user_id,file_id,record_generation) VALUES (2,'actor','file',REPEAT('d',32))");
            return List.of(0.1f);
        });
        when(vectors.search(any())).thenReturn(List.of(hit("new-vector", "替换后的受限内容")));
        assertTrue(controller.retrieve(request(false)).getData().empty());
        assertEquals(1, db.jdbc().queryForObject("SELECT COUNT(*) FROM knowledge_user_file_permission", Integer.class));
    }

    @Test
    void revocationAndRegrantDoNotResumeAnOldRetrieval() {
        when(embedding.embed(anyString(), anyString())).thenAnswer(inv -> {
            db.jdbc().update("DELETE FROM knowledge_user_file_permission WHERE id=1");
            db.jdbc().update("INSERT INTO knowledge_user_file_permission(id,user_id,file_id,record_generation) VALUES (2,'actor','file',REPEAT('d',32))");
            return List.of(0.1f);
        });
        assertTrue(controller.retrieve(request(false)).getData().empty());
    }

    @Test
    void changingThePhysicalKnowledgeBaseIdentityInvalidatesTheSnapshot() {
        when(embedding.embed(anyString(), anyString())).thenAnswer(inv -> {
            db.jdbc().update("UPDATE knowledge_base SET vector_collection_name='replacement_physical' WHERE id=7");
            return List.of(0.1f);
        });
        assertTrue(controller.retrieve(request(false)).getData().empty());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void rerankCannotReleaseContentChangedOrDisabledDuringItsCall(boolean disabled) {
        when(model.rerank(any())).thenAnswer(inv -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            if (disabled) db.jdbc().update("UPDATE knowledge_chunk SET enabled=0 WHERE id=101");
            else db.jdbc().update("UPDATE knowledge_chunk SET content='已经编辑' WHERE id=101");
            return ApiResult.ok(new ModelServiceClient.RerankResult("rerank", "fixture", List.of(new ModelServiceClient.RerankItem(0, 1f, "原文件内容"))));
        });
        assertTrue(controller.retrieve(request(true)).getData().empty());
        verify(model).rerank(any());
    }

    @Test
    void ragDropsAnswerAndReferencesWhenGrantChangesDuringGeneration() {
        var llm = mock(LlmService.class); var prompt = mock(PromptBuilder.class);
        when(prompt.build(anyString(), anyList())).thenReturn("已授权上下文");
        when(llm.chat(anyString(), eq("answer"))).thenAnswer(inv -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            db.jdbc().update("DELETE FROM knowledge_user_file_permission WHERE id=1");
            return "应丢弃的生成结果";
        });
        var response = new RagServiceImpl(core, knowledge, llm, prompt).query(ragRequest());
        assertTrue(response.getReferences().isEmpty()); assertFalse(response.getAnswer().contains("应丢弃"));
        verify(llm).chat(anyString(), eq("answer"));
    }

    @Test
    void anUnchangedGrantAllowsRagGenerationOutsideADatabaseTransaction() {
        var llm = mock(LlmService.class); var prompt = mock(PromptBuilder.class);
        when(prompt.build(anyString(), anyList())).thenReturn("已授权上下文");
        when(llm.chat(eq("已授权上下文"), eq("answer"))).thenAnswer(inv -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive()); return "正常回答";
        });
        var response = new RagServiceImpl(core, knowledge, llm, prompt).query(ragRequest());
        assertEquals("正常回答", response.getAnswer()); assertEquals(1, response.getReferences().size());
        assertEquals("原文件内容", response.getReferences().get(0).getContent());
    }

    @Test
    void revocationWhileBuildingThePromptPreventsTheModelCall() {
        var llm = mock(LlmService.class); var prompt = mock(PromptBuilder.class);
        when(prompt.build(anyString(), anyList())).thenAnswer(inv -> {
            db.jdbc().update("DELETE FROM knowledge_user_file_permission WHERE id=1"); return "原上下文";
        });
        assertTrue(new RagServiceImpl(core, knowledge, llm, prompt).query(ragRequest()).getReferences().isEmpty());
        verifyNoInteractions(llm);
    }

    @ParameterizedTest
    @ValueSource(strings = {"read", "write", "admin"})
    void documentedGrantTypesPermitReading(String type) {
        db.jdbc().update("UPDATE knowledge_user_file_permission SET permission_type=? WHERE id=1", type);
        assertEquals(1, controller.retrieve(request(false)).getData().hitCount());
    }

    @Test
    void unknownGrantTypesFailClosed() {
        db.jdbc().update("UPDATE knowledge_user_file_permission SET permission_type='unknown' WHERE id=1");
        assertTrue(controller.retrieve(request(false)).getData().empty());
        verifyNoInteractions(embedding, vectors, model);
    }

    @Test
    void databaseReadFailureCannotReleaseContent() {
        when(embedding.embed(anyString(), anyString())).thenAnswer(inv -> {
            db.jdbc().execute("DROP TABLE knowledge_user_file_permission"); return List.of(0.1f);
        });
        assertThrows(RuntimeException.class, () -> controller.retrieve(request(true)));
        verifyNoInteractions(model);
    }

    @Test
    void permissionSnapshotsAreNeitherAcceptedNorExposedByJson() throws Exception {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var request = mapper.readValue("{\"query\":\"文件\",\"accessSnapshot\":{\"userId\":\"forged\",\"grants\":{}}}",
                com.enterprise.ai.domain.dto.RetrievalTestRequest.class);
        assertNull(request.getAccessSnapshot());
        var response = core.retrieve(com.enterprise.ai.retrieval.KnowledgeRetrievalCoreRequest.builder()
                .query("文件").userId("actor").topK(5).scoreThreshold(0.1f).searchMode("vector").rerankEnabled(false).build());
        assertNotNull(response.getAccessSnapshot()); assertEquals(1, response.getItems().size());
        String serialized = mapper.writeValueAsString(response);
        assertFalse(serialized.contains("accessSnapshot")); assertFalse(serialized.contains("grantId"));
    }

    private RagRequest ragRequest() {
        var request = new RagRequest(); request.setUserId("actor"); request.setQuestion("文件"); request.setKnowledgeBaseCodes(List.of("kb")); return request;
    }

    private void replaceDuringEmbedding() {
        when(embedding.embed(anyString(), anyString())).thenAnswer(inv -> {
            replaceFile(true);
            return List.of(0.1f);
        });
        when(vectors.search(any())).thenReturn(List.of(hit("new-vector", "替换后的受限内容")));
    }

    @Test
    void aReusedFilePrimaryKeyDoesNotReviveAnOldPermissionSnapshot() {
        var snapshot = permissions.capture("actor");
        var repository = db.mapper(FileInfoRepository.class);
        repository.deleteById(11L);
        var replacement = new com.enterprise.ai.domain.entity.FileInfo(); replacement.setId(11L);
        replacement.setFileId("file"); replacement.setKnowledgeBaseId(7L); replacement.setFileName("复用主键的新文件");
        repository.insert(replacement);
        assertTrue(permissions.resolveAuthorizedChunks(snapshot, List.of(101L)).isEmpty(),
                "Reusing a file primary key must not restore the original file identity");
    }

    @Test
    void aReusedGrantPrimaryKeyDoesNotReviveARevokedSnapshot() {
        var snapshot = permissions.capture("actor");
        var repository = db.mapper(UserFilePermissionRepository.class);
        repository.deleteById(1L);
        var replacement = new com.enterprise.ai.domain.entity.UserFilePermission(); replacement.setId(1L);
        replacement.setUserId("actor"); replacement.setFileId("file"); replacement.setPermissionType("read");
        repository.insert(replacement);
        assertTrue(permissions.resolveAuthorizedChunks(snapshot, List.of(101L)).isEmpty(),
                "Reusing a grant primary key must not restore a revoked request identity");
    }

    private void replaceFile(boolean revokeGrant) {
        if (revokeGrant) db.jdbc().update("DELETE FROM knowledge_user_file_permission WHERE id=1");
        db.jdbc().update("DELETE FROM knowledge_chunk WHERE id=101");
        db.jdbc().update("DELETE FROM knowledge_file_info WHERE id=11");
        db.jdbc().update("INSERT INTO knowledge_file_info(id,file_id,knowledge_base_id,file_name,record_generation) VALUES (12,'file',7,'替换文件',REPEAT('b',32))");
        db.jdbc().update("INSERT INTO knowledge_chunk(id,file_id,knowledge_base_id,chunk_index,content,vector_id,enabled) VALUES (102,'file',7,0,'替换后的受限内容','new-vector',1)");
    }

    private static VectorSearchResult hit(String id, String content) {
        return VectorSearchResult.builder().id(id).score(0.95f).fields(Map.of("file_id", "file", "content", content)).build();
    }

    private static KnowledgeRetrievalInternalController.RetrievalRequest request(boolean rerank) {
        return new KnowledgeRetrievalInternalController.RetrievalRequest("文件", List.of("kb"), "actor", 5, 0.1f, "vector", rerank);
    }
}
