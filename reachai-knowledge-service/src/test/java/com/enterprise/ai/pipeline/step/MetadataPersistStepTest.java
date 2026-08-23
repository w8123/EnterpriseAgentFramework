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
        knowledgeBase.setId(7L);
        when(knowledgeBases.selectOne(any())).thenReturn(knowledgeBase);
        FileInfo existing = new FileInfo();
        existing.setFileId("file_1");
        existing.setImportJobId("job_1");
        when(files.selectOne(any())).thenReturn(existing);

        new MetadataPersistStep(knowledgeBases, files, chunks, new ObjectMapper()).process(context("job_1"));

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
        knowledgeBase.setId(7L);
        when(knowledgeBases.selectOne(any())).thenReturn(knowledgeBase);
        FileInfo existing = new FileInfo();
        existing.setFileId("file_1");
        existing.setImportJobId("another_job");
        when(files.selectOne(any())).thenReturn(existing);

        assertThrows(PipelineException.class,
                () -> new MetadataPersistStep(knowledgeBases, files, chunks, new ObjectMapper())
                        .process(context("job_1")));

        verify(chunks, never()).delete(any());
        verify(files, never()).delete(any());
        verify(files, never()).insert(any(FileInfo.class));
    }

    private static PipelineContext context(String importJobId) {
        PipelineContext context = new PipelineContext();
        context.setFileId("file_1");
        context.setFileName("contract.pdf");
        context.setKnowledgeBaseCode("kb_contract");
        context.setImportJobId(importJobId);
        context.setChunks(List.of("contract text"));
        context.setVectorIds(List.of("file_1_chunk_0"));
        context.setRawText("contract text");
        return context;
    }
}
