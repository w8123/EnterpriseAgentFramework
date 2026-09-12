package com.enterprise.ai.service.impl;

import com.enterprise.ai.domain.dto.DocumentImportJobResponse;
import com.enterprise.ai.domain.dto.DocumentImportAccessContext;
import com.enterprise.ai.domain.entity.DocumentImportJob;
import com.enterprise.ai.domain.entity.KnowledgeBase;
import com.enterprise.ai.pipeline.PipelineContext;
import com.enterprise.ai.pipeline.chunk.ChunkStrategyFactory;
import com.enterprise.ai.pipeline.chunk.impl.FixedLengthChunkStrategy;
import com.enterprise.ai.pipeline.document.DocumentFormat;
import com.enterprise.ai.pipeline.document.DocumentParseResult;
import com.enterprise.ai.pipeline.document.DocumentProviderType;
import com.enterprise.ai.pipeline.document.DocumentSourceLocator;
import com.enterprise.ai.pipeline.document.ParsedDocumentElement;
import com.enterprise.ai.pipeline.document.artifact.DocumentArtifactStore;
import com.enterprise.ai.pipeline.document.job.DocumentImportJobProperties;
import com.enterprise.ai.pipeline.document.job.DocumentImportJobWorker;
import com.enterprise.ai.pipeline.step.ChunkStep;
import com.enterprise.ai.pipeline.step.TextCleanStep;
import com.enterprise.ai.repository.DocumentImportJobRepository;
import com.enterprise.ai.repository.FileInfoRepository;
import com.enterprise.ai.repository.KnowledgeBaseRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.TaskExecutor;

import java.io.ByteArrayInputStream;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DocumentImportJobServiceImplTest {

    @Test
    void previewUsesTheSameStructuredCleanAndChunkStagesAsFormalIndexing() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        DocumentParseResult parsed = DocumentParseResult.builder()
                .providerType(DocumentProviderType.DOCLING)
                .providerVersion("1.30.0")
                .format(DocumentFormat.PDF)
                .normalizedText("Heading\n\nFirst paragraph\n\nSecond paragraph")
                .elements(List.of(
                        ParsedDocumentElement.builder().order(0).type("HEADING").text("Heading").build(),
                        ParsedDocumentElement.builder().order(1).type("PARAGRAPH").text("First paragraph")
                                .sectionPath("Heading")
                                .sourceLocator(DocumentSourceLocator.builder().pageStart(2).pageEnd(2).build())
                                .build(),
                        ParsedDocumentElement.builder().order(2).type("PARAGRAPH").text("Second paragraph").build()))
                .build();
        DocumentImportJob job = new DocumentImportJob();
        job.setJobId("dij_preview_test");
        job.setFileId("file_preview_test");
        job.setKnowledgeBaseCode("kb_preview_test");
        job.setTenantId("default");
        job.setCreatedByActorId("42");
        job.setFileName("contract.pdf");
        job.setFileType("pdf");
        job.setProviderType("DOCLING");
        job.setStatus("PARSED");
        job.setStage("PARSED");
        job.setChunkStrategy("fixed_length");
        job.setChunkSize(500);
        job.setChunkOverlap(50);
        job.setExtraParamsJson("{}");
        job.setParseArtifactObjectKey("jobs/preview/parsed/document.json");

        DocumentImportJobRepository jobs = mock(DocumentImportJobRepository.class);
        when(jobs.selectOne(any())).thenReturn(job);
        DocumentArtifactStore artifacts = mock(DocumentArtifactStore.class);
        when(artifacts.open(job.getParseArtifactObjectKey()))
                .thenReturn(new ByteArrayInputStream(objectMapper.writeValueAsBytes(parsed)));
        TextCleanStep textCleanStep = new TextCleanStep();
        ChunkStep chunkStep = new ChunkStep(new ChunkStrategyFactory(List.of(new FixedLengthChunkStrategy())));
        TaskExecutor taskExecutor = Runnable::run;
        DocumentImportJobServiceImpl service = new DocumentImportJobServiceImpl(jobs, mock(KnowledgeBaseRepository.class), mock(FileInfoRepository.class), artifacts, mock(com.enterprise.ai.pipeline.document.DocumentParseRouter.class), objectMapper, new DocumentImportJobProperties(), mock(DocumentImportJobWorker.class), textCleanStep, chunkStep, taskExecutor, com.enterprise.ai.support.ArtifactLifecycleTestSupport.transactions());

        PipelineContext expected = new PipelineContext();
        expected.setFileId(job.getFileId());
        expected.setRawText(parsed.getNormalizedText());
        expected.setParsedDocument(parsed);
        expected.setChunkStrategy(job.getChunkStrategy());
        expected.setChunkSize(job.getChunkSize());
        expected.setChunkOverlap(job.getChunkOverlap());
        textCleanStep.process(expected);
        chunkStep.process(expected);

        DocumentImportJobResponse response = service.get(job.getJobId(), true, access());

        assertEquals(expected.getChunks(), response.getPreview().getChunks().stream()
                .map(item -> item.getContent())
                .collect(Collectors.toList()));
        assertEquals(3, response.getPreview().getTotalChunks());
        assertEquals("PARAGRAPH", response.getPreview().getChunks().get(1).getElementType());
        assertEquals("Heading", response.getPreview().getChunks().get(1).getSectionPath());
        assertEquals(2, objectMapper.readTree(
                response.getPreview().getChunks().get(1).getSourceLocatorJson()).path("pageStart").asInt());
    }

    @Test
    void retryPreservesManualPreviewInsteadOfForcingAutoCommit() {
        DocumentImportJob job = new DocumentImportJob();
        job.setJobId("dij_retry_preview");
        job.setFileId("file_retry_preview");
        job.setKnowledgeBaseCode("kb_preview_test"); job.setKnowledgeBaseId(7L); job.setVectorCollectionName("physical-preview");
        job.setTenantId("default");
        job.setCreatedByActorId("42");
        job.setFileName("contract.pdf");
        job.setFileType("pdf");
        job.setProviderType("DOCLING");
        job.setStatus("FAILED");
        job.setStage("PARSING");
        job.setAutoCommit(0);
        job.setAttemptCount(1);
        job.setMaxAttempts(3);

        DocumentImportJobRepository jobs = mock(DocumentImportJobRepository.class);
        when(jobs.selectOne(any())).thenReturn(job);
        KnowledgeBaseRepository targetBases = mock(KnowledgeBaseRepository.class);
        KnowledgeBase target = new KnowledgeBase(); target.setId(7L); target.setCode("kb_preview_test"); target.setVectorCollectionName("physical-preview");
        when(targetBases.selectById(7L)).thenReturn(target);
        DocumentImportJobServiceImpl service = new DocumentImportJobServiceImpl(jobs, targetBases, mock(FileInfoRepository.class), mock(DocumentArtifactStore.class), mock(com.enterprise.ai.pipeline.document.DocumentParseRouter.class), new ObjectMapper(), new DocumentImportJobProperties(), mock(DocumentImportJobWorker.class), new TextCleanStep(), new ChunkStep(new ChunkStrategyFactory(List.of(new FixedLengthChunkStrategy()))), Runnable::run, com.enterprise.ai.support.ArtifactLifecycleTestSupport.transactions());

        DocumentImportJobResponse response = service.retry(job.getJobId(), access());

        assertEquals("QUEUED", response.getStatus());
        assertFalse(response.getAutoCommit());
        assertEquals(0, job.getAutoCommit());
    }

    @Test
    void rejectsUnsafeChunkConfigurationBeforePersistingArtifacts() {
        KnowledgeBase knowledgeBase = new KnowledgeBase();
        knowledgeBase.setId(7L);
        knowledgeBase.setCode("kb_validation"); knowledgeBase.setVectorCollectionName("kb_validation");
        knowledgeBase.setWorkspaceId("default");
        knowledgeBase.setScope("WORKSPACE");
        KnowledgeBaseRepository knowledgeBases = mock(KnowledgeBaseRepository.class);
        when(knowledgeBases.selectOne(any())).thenReturn(knowledgeBase);
        DocumentImportJobServiceImpl service = new DocumentImportJobServiceImpl(mock(DocumentImportJobRepository.class), knowledgeBases, mock(FileInfoRepository.class), mock(DocumentArtifactStore.class), mock(com.enterprise.ai.pipeline.document.DocumentParseRouter.class), new ObjectMapper(), new DocumentImportJobProperties(), mock(DocumentImportJobWorker.class), new TextCleanStep(), new ChunkStep(new ChunkStrategyFactory(List.of(new FixedLengthChunkStrategy()))), Runnable::run, com.enterprise.ai.support.ArtifactLifecycleTestSupport.transactions());
        org.springframework.mock.web.MockMultipartFile file = new org.springframework.mock.web.MockMultipartFile(
                "file", "contract.txt", "text/plain", "content".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        DocumentImportAccessContext scoped = new DocumentImportAccessContext(
                "default", "42", "default", null, "WORKSPACE");

        assertThrows(IllegalArgumentException.class, () -> service.submit(
                file, "kb_validation", "fixed_length", -1, 0, Map.of(), false, scoped));
        assertThrows(IllegalArgumentException.class, () -> service.submit(
                file, "kb_validation", "fixed_length", 100, 100, Map.of(), false, scoped));
        assertThrows(IllegalArgumentException.class, () -> service.submit(
                file, "kb_validation", "unknown", 500, 50, Map.of(), false, scoped));
    }

    private static DocumentImportAccessContext access() {
        return new DocumentImportAccessContext("default", "42", null, null, null);
    }
}
