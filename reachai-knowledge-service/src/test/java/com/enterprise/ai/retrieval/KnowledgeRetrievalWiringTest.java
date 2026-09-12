package com.enterprise.ai.retrieval;

import com.enterprise.ai.client.ModelServiceClient;
import com.enterprise.ai.domain.dto.RetrievalTestRequest;
import com.enterprise.ai.domain.entity.Chunk;
import com.enterprise.ai.domain.entity.KnowledgeBase;
import com.enterprise.ai.embedding.EmbeddingService;
import com.enterprise.ai.pipeline.document.artifact.DocumentArtifactStore;
import com.enterprise.ai.repository.*;
import com.enterprise.ai.service.KnowledgeService;
import com.enterprise.ai.service.impl.KnowledgeServiceImpl;
import com.enterprise.ai.service.impl.KnowledgeOperationsQuery;
import com.enterprise.ai.vector.VectorService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class KnowledgeRetrievalWiringTest {
    @Test
    void adminAndRuntimeUseOneEngineWithTheSameDefaultsAndFileScope() {
        var bases = mock(KnowledgeBaseRepository.class);
        var chunks = mock(ChunkRepository.class);
        var embedding = mock(EmbeddingService.class);
        var vector = mock(VectorService.class);
        var base = new KnowledgeBase(); base.setId(1L); base.setCode("orders"); base.setVectorCollectionName("orders"); base.setStatus(1);
        when(bases.selectList(any())).thenReturn(List.of(base));
        when(chunks.selectList(any())).thenReturn(List.of(chunk(1L, "f1", "订单一"),
                chunk(2L, "f1", "订单二"), chunk(3L, "f2", "订单三")));
        var permissions = mock(UserFilePermissionRepository.class);
        when(permissions.selectFileGrantsByUserId("actor")).thenReturn(List.of(
                new com.enterprise.ai.security.FileAccessSnapshot.Grant(1L, "a".repeat(32), 11L, "b".repeat(32), 1L, "f1", "orders")));
        when(permissions.selectAuthorizedChunks(eq("actor"), anyList())).thenReturn(List.of(
                new com.enterprise.ai.security.AuthorizedKnowledgeChunk(1L, 1L, "a".repeat(32), 11L, "b".repeat(32), 1L, "f1", "orders", "orders", "v1", "订单一", "订单文件"),
                new com.enterprise.ai.security.AuthorizedKnowledgeChunk(2L, 1L, "a".repeat(32), 11L, "b".repeat(32), 1L, "f1", "orders", "orders", "v2", "订单二", "订单文件")));
        new ApplicationContextRunner()
                .withUserConfiguration(KnowledgeBaseLookup.class, DefaultKnowledgeRetrievalEngine.class,
                        KnowledgeServiceImpl.class, KnowledgeOperationsQuery.class, KnowledgeRetrievalCore.class,
                        com.enterprise.ai.service.impl.KnowledgeContentQuery.class,
                        com.enterprise.ai.service.impl.KnowledgeChunkEditingService.class,
                        com.enterprise.ai.service.impl.KnowledgeTagService.class,
                        com.enterprise.ai.service.impl.KnowledgeQuestionService.class,
                        KnowledgeRetrievalAuthorization.class, com.enterprise.ai.security.impl.PermissionServiceImpl.class)
                .withBean(org.springframework.transaction.PlatformTransactionManager.class,
                        com.enterprise.ai.support.ArtifactLifecycleTestSupport::transactions)
                .withPropertyValues("rag.default-top-k=2", "rag.score-threshold=0.7")
                .withBean(com.enterprise.ai.service.impl.KnowledgeIndexWriteService.class,
                        () -> mock(com.enterprise.ai.service.impl.KnowledgeIndexWriteService.class))
                .withBean(com.enterprise.ai.service.impl.KnowledgeFileDeletionService.class,
                        () -> mock(com.enterprise.ai.service.impl.KnowledgeFileDeletionService.class))
                .withBean(com.enterprise.ai.service.impl.KnowledgeBaseLifecycleService.class,
                        () -> mock(com.enterprise.ai.service.impl.KnowledgeBaseLifecycleService.class))
                .withBean(KnowledgeBaseRepository.class, () -> bases)
                .withBean(ChunkRepository.class, () -> chunks)
                .withBean(FileInfoRepository.class, () -> mock(FileInfoRepository.class))
                .withBean(KnowledgeTagRepository.class, () -> mock(KnowledgeTagRepository.class))
                .withBean(KnowledgeQuestionRepository.class, () -> mock(KnowledgeQuestionRepository.class))
                .withBean(UserFilePermissionRepository.class, () -> permissions)
                .withBean(KnowledgeHitLogRepository.class, () -> mock(KnowledgeHitLogRepository.class))
                .withBean(EmbeddingService.class, () -> embedding)
                .withBean(ModelServiceClient.class, () -> mock(ModelServiceClient.class))
                .withBean(VectorService.class, () -> vector)
                .withBean(DocumentArtifactStore.class, () -> mock(DocumentArtifactStore.class))
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    assertEquals(1, context.getBeanNamesForType(KnowledgeRetrievalEngine.class).length);
                    var adminRequest = new RetrievalTestRequest(); adminRequest.setQuery("订单");
                    adminRequest.setKnowledgeBaseCodes(List.of("orders")); adminRequest.setSearchMode("keyword");
                    adminRequest.setAccessibleFileIds(List.of("f1")); adminRequest.setRerankEnabled(false);
                    var admin = context.getBean(KnowledgeService.class).retrievalTest(adminRequest);
                    var runtime = context.getBean(KnowledgeRetrievalCore.class).retrieve(KnowledgeRetrievalCoreRequest.builder()
                            .query("订单").knowledgeBaseCodes(List.of("orders")).searchMode("keyword").userId("actor")
                            .accessibleFileIds(List.of("f1")).rerankEnabled(false).build());
                    assertEquals(List.of("订单一", "订单二"), admin.getItems().stream().map(item -> item.getContent()).toList());
                    assertEquals(admin.getItems().stream().map(item -> item.getContent()).toList(),
                            runtime.getItems().stream().map(item -> item.getContent()).toList());
                    verifyNoInteractions(embedding, vector);
                });
    }

    private static Chunk chunk(Long id, String fileId, String content) {
        var chunk = new Chunk(); chunk.setId(id); chunk.setKnowledgeBaseId(1L); chunk.setFileId(fileId);
        chunk.setVectorId("v" + id); chunk.setEnabled(1); chunk.setContent(content); return chunk;
    }
}
