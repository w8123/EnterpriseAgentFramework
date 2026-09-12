package com.enterprise.ai.pipeline.step;

import com.enterprise.ai.domain.entity.Chunk;
import com.enterprise.ai.domain.entity.FileInfo;
import com.enterprise.ai.domain.entity.KnowledgeBase;
import com.enterprise.ai.pipeline.PipelineContext;
import com.enterprise.ai.pipeline.PipelineException;
import com.enterprise.ai.repository.ChunkRepository;
import com.enterprise.ai.repository.FileInfoRepository;
import com.enterprise.ai.repository.KnowledgeBaseRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InOrder;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MetadataPersistStepTest {

    @Test
    void replacesMetadataOwnedByTheSameImportJobBeforeReinserting() {
        KnowledgeBaseRepository knowledgeBases = mock(KnowledgeBaseRepository.class);
        FileInfoRepository files = mock(FileInfoRepository.class);
        ChunkRepository chunks = mock(ChunkRepository.class);
        KnowledgeBase knowledgeBase = new KnowledgeBase();
        knowledgeBase.setId(7L); knowledgeBase.setCode("kb_contract"); knowledgeBase.setVectorCollectionName("kb_contract");
        when(knowledgeBases.selectById(any())).thenReturn(knowledgeBase);
        FileInfo existing = new FileInfo();
        existing.setFileId("file_1");
        existing.setImportJobId("job_1");
        existing.setKnowledgeBaseId(7L);
        when(files.selectOne(any())).thenReturn(existing);

        new MetadataPersistStep(knowledgeBases, files, chunks, new ObjectMapper(), mock(com.enterprise.ai.pipeline.document.job.DocumentImportPublicationGuard.class), org.mockito.Mockito.mock(com.enterprise.ai.service.impl.KnowledgeTagService.class), org.mockito.Mockito.mock(com.enterprise.ai.service.impl.KnowledgeQuestionService.class)).process(context("job_1"));

        InOrder order = inOrder(chunks, files);
        order.verify(chunks).delete(any());
        order.verify(files).delete(any());
        order.verify(files).insert(any(FileInfo.class));
        order.verify(chunks).insert(any(Chunk.class));
    }

    @Test
    void refusesToOverwriteAFileOwnedByAnotherImportJob() {
        KnowledgeBaseRepository knowledgeBases = mock(KnowledgeBaseRepository.class);
        FileInfoRepository files = mock(FileInfoRepository.class);
        ChunkRepository chunks = mock(ChunkRepository.class);
        KnowledgeBase knowledgeBase = new KnowledgeBase();
        knowledgeBase.setId(7L); knowledgeBase.setCode("kb_contract"); knowledgeBase.setVectorCollectionName("kb_contract");
        when(knowledgeBases.selectById(any())).thenReturn(knowledgeBase);
        FileInfo existing = new FileInfo();
        existing.setFileId("file_1");
        existing.setImportJobId("another_job");
        when(files.selectOne(any())).thenReturn(existing);

        assertThrows(PipelineException.class,
                () -> new MetadataPersistStep(knowledgeBases, files, chunks, new ObjectMapper(), mock(com.enterprise.ai.pipeline.document.job.DocumentImportPublicationGuard.class), org.mockito.Mockito.mock(com.enterprise.ai.service.impl.KnowledgeTagService.class), org.mockito.Mockito.mock(com.enterprise.ai.service.impl.KnowledgeQuestionService.class))
                        .process(context("job_1")));

        verify(chunks, never()).delete(any());
        verify(files, never()).delete(any());
        verify(files, never()).insert(any(FileInfo.class));
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "job_1"})
    void matchingJobIdentityCannotReplaceMetadataFromAnotherKnowledgeBase(String jobId) {
        var knowledgeBases = mock(KnowledgeBaseRepository.class);
        var files = mock(FileInfoRepository.class);
        var chunks = mock(ChunkRepository.class);
        var knowledgeBase = new KnowledgeBase(); knowledgeBase.setId(7L); knowledgeBase.setCode("kb_contract"); knowledgeBase.setVectorCollectionName("kb_contract");
        when(knowledgeBases.selectById(any())).thenReturn(knowledgeBase);
        var existing = new FileInfo(); existing.setFileId("file_1");
        existing.setKnowledgeBaseId(8L); existing.setImportJobId(jobId);
        when(files.selectOne(any())).thenReturn(existing);

        assertThrows(PipelineException.class,
                () -> new MetadataPersistStep(knowledgeBases, files, chunks, new ObjectMapper(), mock(com.enterprise.ai.pipeline.document.job.DocumentImportPublicationGuard.class), org.mockito.Mockito.mock(com.enterprise.ai.service.impl.KnowledgeTagService.class), org.mockito.Mockito.mock(com.enterprise.ai.service.impl.KnowledgeQuestionService.class)).process(context(jobId)));

        verify(chunks, never()).delete(any());
        verify(files, never()).delete(any());
        verify(files, never()).insert(any(FileInfo.class));
        verify(chunks, never()).insert(any(Chunk.class));
    }

    private static PipelineContext context(String importJobId) {
        PipelineContext context = new PipelineContext();
        context.setFileId("file_1");
        context.setFileName("contract.pdf");
        context.setKnowledgeBaseCode("kb_contract"); context.setKnowledgeBaseId(7L); context.setVectorCollectionName("kb_contract");
        context.setImportJobId(importJobId);
        context.setChunks(List.of("contract text"));
        context.setVectorIds(List.of("file_1_chunk_0"));
        context.setRawText("contract text");
        return context;
    }
}
