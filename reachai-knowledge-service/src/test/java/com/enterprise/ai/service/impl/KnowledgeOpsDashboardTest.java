package com.enterprise.ai.service.impl;

import com.enterprise.ai.domain.entity.*;
import com.enterprise.ai.embedding.EmbeddingService;
import com.enterprise.ai.pipeline.document.artifact.DocumentArtifactStore;
import com.enterprise.ai.repository.*;
import com.enterprise.ai.retrieval.KnowledgeRetrievalEngine;
import com.enterprise.ai.vector.VectorService;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class KnowledgeOpsDashboardTest {
    @ParameterizedTest
    @NullSource
    @ValueSource(ints = {0, 1, 2})
    void aggregateFileStatusDoesNotInventStageEvidence(Integer status) {
        var files = mock(FileInfoRepository.class);
        var chunks = mock(ChunkRepository.class);
        var hits = mock(KnowledgeHitLogRepository.class);
        var lookup = mock(KnowledgeBaseLookup.class);
        var base = new KnowledgeBase(); base.setId(42L); base.setCode("orders"); base.setVectorCollectionName("orders");
        when(lookup.requireByCode("orders")).thenReturn(base);
        var file = new FileInfo(); file.setFileId("file-1"); file.setKnowledgeBaseId(42L);
        file.setFileName("orders.txt"); file.setStatus(status); file.setChunkCount(7);
        when(files.selectList(any())).thenReturn(List.of(file));
        when(chunks.selectList(any())).thenReturn(List.of());
        when(hits.selectList(any())).thenReturn(List.of());
        var service = KnowledgeManagementTestServices.create(files, chunks, mock(KnowledgeTagRepository.class), org.mockito.Mockito.mock(com.enterprise.ai.service.impl.KnowledgeQuestionService.class), new KnowledgeOperationsQuery(mock(KnowledgeBaseRepository.class), files, chunks,
                        mock(KnowledgeTagRepository.class), mock(KnowledgeQuestionRepository.class), hits, lookup), mock(KnowledgeIndexWriteService.class), lookup, mock(KnowledgeRetrievalEngine.class), org.mockito.Mockito.mock(KnowledgeFileDeletionService.class), org.mockito.Mockito.mock(com.enterprise.ai.service.impl.KnowledgeBaseLifecycleService.class), org.mockito.Mockito.mock(com.enterprise.ai.service.impl.KnowledgeTagService.class));

        var dashboard = service.getOpsDashboard("orders");

        assertEquals(1, dashboard.getRecentFiles().size());
        var projected = dashboard.getRecentFiles().get(0);
        assertEquals("file-1", projected.getFileId());
        assertEquals("orders.txt", projected.getFileName());
        assertEquals(status, projected.getStatus());
        assertEquals(7, projected.getChunkCount());
        assertTrue(projected.getSteps().isEmpty(), "File aggregate status is not evidence for each pipeline stage");
    }
}
