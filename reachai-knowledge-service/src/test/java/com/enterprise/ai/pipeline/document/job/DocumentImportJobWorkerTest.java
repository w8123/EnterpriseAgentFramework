package com.enterprise.ai.pipeline.document.job;

import com.enterprise.ai.domain.dto.PipelineResult;
import com.enterprise.ai.domain.entity.DocumentImportJob;
import com.enterprise.ai.pipeline.PipelineContext;
import com.enterprise.ai.pipeline.document.DocumentFormat;
import com.enterprise.ai.pipeline.document.DocumentParseErrorCode;
import com.enterprise.ai.pipeline.document.DocumentParseException;
import com.enterprise.ai.pipeline.document.DocumentParseResult;
import com.enterprise.ai.pipeline.document.DocumentParseRouter;
import com.enterprise.ai.pipeline.document.DocumentProviderType;
import com.enterprise.ai.pipeline.document.ParsedDocumentElement;
import com.enterprise.ai.pipeline.document.artifact.DocumentArtifactStore;
import com.enterprise.ai.repository.DocumentImportJobRepository;
import com.enterprise.ai.service.PipelineImportService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DocumentImportJobWorkerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void persistsOneStructuredParseArtifactBeforePreviewOrIndexing() throws Exception {
        DocumentImportJob job = queuedJob();
        DocumentImportJobRepository jobs = parsingRepository(job);
        InMemoryArtifactStore artifacts = new InMemoryArtifactStore();
        artifacts.put(job.getSourceObjectKey(), new ByteArrayInputStream("source".getBytes(StandardCharsets.UTF_8)),
                6, "application/pdf");
        DocumentParseRouter router = mock(DocumentParseRouter.class);
        when(router.parse(any())).thenReturn(parsedResult());
        PipelineImportService pipeline = mock(PipelineImportService.class);

        worker(jobs, artifacts, router, pipeline).process(job.getJobId());

        assertEquals(DocumentImportJobStatus.PARSED.name(), job.getStatus());
        assertEquals("DOCLING", job.getProviderType());
        assertEquals("1.30.0", job.getProviderVersion());
        assertNotNull(job.getParsedAt());
        assertNotNull(job.getParseArtifactObjectKey());
        assertTrue(artifacts.contains(job.getParseArtifactObjectKey()));
        DocumentParseResult persisted = objectMapper.readValue(artifacts.bytes(job.getParseArtifactObjectKey()),
                DocumentParseResult.class);
        assertEquals("Docling text", persisted.getNormalizedText());
        assertEquals("Heading", persisted.getElements().get(0).getSectionPath());
        verify(pipeline, never()).execute(any());
    }

    @Test
    void retryableDoclingFailureWaitsForTheSameProviderAndKeepsTheSourceArtifact() {
        DocumentImportJob job = queuedJob();
        DocumentImportJobRepository jobs = parsingRepository(job);
        InMemoryArtifactStore artifacts = new InMemoryArtifactStore();
        artifacts.put(job.getSourceObjectKey(), new ByteArrayInputStream("source".getBytes(StandardCharsets.UTF_8)),
                6, "application/pdf");
        DocumentParseRouter router = mock(DocumentParseRouter.class);
        when(router.parse(any())).thenThrow(new DocumentParseException(DocumentParseErrorCode.DOCLING_TIMEOUT,
                "Docling timed out"));
        PipelineImportService pipeline = mock(PipelineImportService.class);

        worker(jobs, artifacts, router, pipeline).process(job.getJobId());

        assertEquals(DocumentImportJobStatus.RETRY_WAIT.name(), job.getStatus());
        assertEquals("PARSING", job.getStage());
        assertEquals(DocumentParseErrorCode.DOCLING_TIMEOUT.name(), job.getErrorCode());
        assertNotNull(job.getNextAttemptAt());
        assertEquals(1, job.getAttemptCount());
        assertEquals(DocumentProviderType.DOCLING.name(), job.getProviderType());
        assertTrue(artifacts.contains(job.getSourceObjectKey()));
        verify(pipeline, never()).execute(any());
    }

    @Test
    void autoCommitIndexesTheImmutableParseArtifactWithoutCallingDoclingAgain() throws Exception {
        DocumentImportJob job = queuedJob();
        job.setStatus(DocumentImportJobStatus.PARSED.name());
        job.setStage("PARSED");
        job.setAutoCommit(1);
        job.setProviderVersion("1.30.0");
        job.setParseArtifactObjectKey("jobs/job-1/parsed/document.json");
        DocumentImportJobRepository jobs = mock(DocumentImportJobRepository.class);
        when(jobs.selectOne(any())).thenReturn(job);
        when(jobs.claimForIndexing(anyString(), anyString(), anyInt())).thenAnswer(invocation -> {
            job.setStatus(DocumentImportJobStatus.INDEXING.name());
            job.setStage("INDEXING");
            job.setLeaseOwner(invocation.getArgument(1));
            job.setLeaseUntil(LocalDateTime.now().plusMinutes(1));
            return 1;
        });
        InMemoryArtifactStore artifacts = new InMemoryArtifactStore();
        artifacts.put(job.getParseArtifactObjectKey(),
                new ByteArrayInputStream(objectMapper.writeValueAsBytes(parsedResult())),
                objectMapper.writeValueAsBytes(parsedResult()).length, "application/json");
        DocumentParseRouter router = mock(DocumentParseRouter.class);
        PipelineImportService pipeline = mock(PipelineImportService.class);
        when(pipeline.execute(any())).thenAnswer(invocation -> {
            PipelineContext published = invocation.getArgument(0);
            published.setImportPublished(true);
            job.setStatus(DocumentImportJobStatus.COMPLETED.name());
            job.setCompletedAt(LocalDateTime.now());
            return PipelineResult.builder().status("SUCCESS").build();
        });

        worker(jobs, artifacts, router, pipeline).process(job.getJobId());

        ArgumentCaptor<PipelineContext> context = ArgumentCaptor.forClass(PipelineContext.class);
        verify(pipeline).execute(context.capture());
        assertEquals("Docling text", context.getValue().getParsedDocument().getNormalizedText());
        assertEquals(job.getParseArtifactObjectKey(), context.getValue().getParseArtifactObjectKey());
        assertEquals(DocumentImportJobStatus.COMPLETED.name(), job.getStatus());
        assertNotNull(job.getCompletedAt());
        verify(jobs, never()).finalizeIndexing(anyString(), anyString(), any());
        verify(router, never()).parse(any());
    }

    @Test
    void failedIndexingCompensatesOnlyDerivedDataAndKeepsTheParseArtifactForRetry() throws Exception {
        DocumentImportJob job = queuedJob();
        job.setStatus(DocumentImportJobStatus.PARSED.name());
        job.setStage("PARSED");
        job.setAutoCommit(1);
        job.setParseArtifactObjectKey("jobs/job-1/parsed/document.json");
        DocumentImportJobRepository jobs = mock(DocumentImportJobRepository.class);
        when(jobs.selectOne(any())).thenReturn(job);
        when(jobs.claimForIndexing(anyString(), anyString(), anyInt())).thenAnswer(invocation -> {
            job.setStatus(DocumentImportJobStatus.INDEXING.name());
            job.setStage("INDEXING");
            job.setLeaseOwner(invocation.getArgument(1));
            job.setLeaseUntil(LocalDateTime.now().plusMinutes(1));
            return 1;
        });
        when(jobs.renewIndexingLease(anyString(), anyString(), anyInt())).thenReturn(1);
        when(jobs.failIndexing(anyString(), anyString(), anyString())).thenAnswer(invocation -> {
            job.setStatus(DocumentImportJobStatus.FAILED.name());
            job.setStage("INDEXING");
            job.setErrorCode("INDEXING_FAILED");
            job.setErrorMessage(invocation.getArgument(2));
            job.setLeaseOwner(null);
            return 1;
        });
        InMemoryArtifactStore artifacts = new InMemoryArtifactStore();
        byte[] parsed = objectMapper.writeValueAsBytes(parsedResult());
        artifacts.put(job.getParseArtifactObjectKey(), new ByteArrayInputStream(parsed), parsed.length,
                "application/json");
        PipelineImportService pipeline = mock(PipelineImportService.class);
        when(pipeline.execute(any())).thenAnswer(invocation -> {
            PipelineContext context = invocation.getArgument(0);
            context.setVectorCollectionName("physical_unit_test");
            context.setVectorIds(List.of("attempt-vector"));
            return PipelineResult.builder().status("FAILED").errorMessage("metadata unavailable").build();
        });
        var vectors = mock(com.enterprise.ai.vector.VectorService.class);

        worker(jobs, artifacts, mock(DocumentParseRouter.class), pipeline, vectors)
                .process(job.getJobId());

        assertEquals(DocumentImportJobStatus.FAILED.name(), job.getStatus());
        assertEquals("INDEXING_FAILED", job.getErrorCode());
        assertTrue(artifacts.contains(job.getParseArtifactObjectKey()));
        verify(vectors).deleteById("physical_unit_test", "attempt-vector");
    }

    @Test
    void staleParserCannotFinalizeAndOnlyDeletesItsAttemptArtifact() throws Exception {
        DocumentImportJob job = queuedJob();
        DocumentImportJobRepository jobs = mock(DocumentImportJobRepository.class);
        when(jobs.selectOne(any())).thenReturn(job);
        when(jobs.claimForParsing(anyString(), anyString(), anyInt())).thenAnswer(invocation -> {
            job.setStatus(DocumentImportJobStatus.PARSING.name());
            job.setStage("PARSING");
            job.setAttemptCount(1);
            job.setLeaseOwner(invocation.getArgument(1));
            job.setLeaseUntil(LocalDateTime.now().plusMinutes(1));
            return 1;
        });
        when(jobs.finalizeParsing(anyString(), anyString(), anyString(), anyString(), anyString(), any()))
                .thenReturn(0);
        InMemoryArtifactStore artifacts = new InMemoryArtifactStore();
        artifacts.put(job.getSourceObjectKey(), new ByteArrayInputStream("source".getBytes(StandardCharsets.UTF_8)),
                6, "application/pdf");
        DocumentParseRouter router = mock(DocumentParseRouter.class);
        when(router.parse(any())).thenReturn(parsedResult());

        worker(jobs, artifacts, router, mock(PipelineImportService.class)).process(job.getJobId());

        ArgumentCaptor<String> staleArtifact = ArgumentCaptor.forClass(String.class);
        verify(jobs).finalizeParsing(anyString(), anyString(), anyString(), anyString(), staleArtifact.capture(), any());
        assertFalse(artifacts.contains(staleArtifact.getValue()));
        verify(jobs, never()).failParsing(anyString(), anyString(), anyString(), any(), anyString(), anyString());
    }

    @Test
    void staleParserAfterManualRetryCannotDeleteTheSuccessfulArtifact() throws Exception {
        DocumentImportJob job = queuedJob();
        DocumentImportJobRepository jobs = parsingRepository(job);
        InMemoryArtifactStore artifacts = new InMemoryArtifactStore();
        DocumentParseRouter router = mock(DocumentParseRouter.class);
        DocumentImportJobWorker worker = worker(jobs, artifacts, router, mock(PipelineImportService.class));
        when(jobs.finalizeParsing(anyString(), anyString(), anyString(), anyString(), anyString(), any()))
                .thenAnswer(invocation -> {
                    if (!invocation.getArgument(1).equals(job.getLeaseOwner())) return 0;
                    job.setStatus(DocumentImportJobStatus.PARSED.name());
                    job.setParseArtifactObjectKey(invocation.getArgument(4));
                    job.setLeaseOwner(null);
                    return 1;
                });
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        job.setStatus(DocumentImportJobStatus.QUEUED.name());
        job.setParseArtifactObjectKey(null);
        when(router.parse(any())).thenAnswer(invocation -> {
            if (calls.incrementAndGet() == 1) {
                // Lease recovery followed by manual retry resets the attempt counter.
                job.setStatus(DocumentImportJobStatus.QUEUED.name());
                job.setAttemptCount(0);
                worker.process(job.getJobId());
                return DocumentParseResult.builder().providerType(DocumentProviderType.DOCLING)
                        .normalizedText("stale result").build();
            }
            return parsedResult();
        });

        worker.process(job.getJobId());

        assertEquals(DocumentImportJobStatus.PARSED.name(), job.getStatus());
        assertTrue(artifacts.contains(job.getParseArtifactObjectKey()));
        assertEquals("Docling text", objectMapper.readValue(artifacts.bytes(job.getParseArtifactObjectKey()),
                DocumentParseResult.class).getNormalizedText());
    }

    @Test
    void sameWorkerInstanceUsesANewLeaseOwnerForEveryParseAttempt() throws Exception {
        DocumentImportJob job = queuedJob();
        DocumentImportJobRepository jobs = mock(DocumentImportJobRepository.class);
        when(jobs.selectOne(any())).thenReturn(job);
        when(jobs.claimForParsing(anyString(), anyString(), anyInt())).thenAnswer(invocation -> {
            job.setStatus(DocumentImportJobStatus.PARSING.name());
            job.setAttemptCount(job.getAttemptCount() + 1);
            job.setLeaseOwner(invocation.getArgument(1));
            job.setLeaseUntil(LocalDateTime.now().plusMinutes(1));
            return 1;
        });
        when(jobs.finalizeParsing(anyString(), anyString(), anyString(), anyString(), anyString(), any()))
                .thenReturn(0);
        InMemoryArtifactStore artifacts = new InMemoryArtifactStore();
        artifacts.put(job.getSourceObjectKey(), new ByteArrayInputStream("source".getBytes(StandardCharsets.UTF_8)),
                6, "application/pdf");
        DocumentParseRouter router = mock(DocumentParseRouter.class);
        when(router.parse(any())).thenReturn(parsedResult());
        DocumentImportJobWorker worker = worker(jobs, artifacts, router, mock(PipelineImportService.class));

        worker.process(job.getJobId());
        job.setStatus(DocumentImportJobStatus.RETRY_WAIT.name());
        worker.process(job.getJobId());

        ArgumentCaptor<String> leaseOwners = ArgumentCaptor.forClass(String.class);
        verify(jobs, org.mockito.Mockito.times(2))
                .claimForParsing(anyString(), leaseOwners.capture(), anyInt());
        assertEquals(2, leaseOwners.getAllValues().size());
        assertNotEquals(leaseOwners.getAllValues().get(0), leaseOwners.getAllValues().get(1));
    }

    @Test
    void successWithoutACommittedPublicationIsTreatedAsAnIndexingFailure() throws Exception {
        DocumentImportJob job = queuedJob();
        job.setStatus(DocumentImportJobStatus.PARSED.name());
        job.setStage("PARSED");
        job.setAutoCommit(1);
        job.setReplaceFileId("file_old");
        job.setParseArtifactObjectKey("jobs/job-1/parsed/document.json");
        DocumentImportJobRepository jobs = mock(DocumentImportJobRepository.class);
        when(jobs.selectOne(any())).thenReturn(job);
        when(jobs.claimForIndexing(anyString(), anyString(), anyInt())).thenAnswer(invocation -> {
            job.setStatus(DocumentImportJobStatus.INDEXING.name());
            job.setLeaseOwner(invocation.getArgument(1));
            job.setLeaseUntil(LocalDateTime.now().plusMinutes(1));
            return 1;
        });
        when(jobs.finalizeIndexing(anyString(), anyString(), any())).thenReturn(0);
        InMemoryArtifactStore artifacts = new InMemoryArtifactStore();
        byte[] parsed = objectMapper.writeValueAsBytes(parsedResult());
        artifacts.put(job.getParseArtifactObjectKey(), new ByteArrayInputStream(parsed), parsed.length,
                "application/json");
        PipelineImportService pipeline = mock(PipelineImportService.class);
        when(pipeline.execute(any())).thenReturn(PipelineResult.builder().status("SUCCESS").build());

        worker(jobs, artifacts, mock(DocumentParseRouter.class), pipeline)
                .process(job.getJobId());

        verify(jobs).failIndexing(anyString(), anyString(), anyString());
    }

    @Test
    void staleFailedIndexerSkipsCompensation() throws Exception {
        DocumentImportJob job = queuedJob();
        job.setStatus(DocumentImportJobStatus.PARSED.name());
        job.setStage("PARSED");
        job.setAutoCommit(1);
        job.setParseArtifactObjectKey("jobs/job-1/parsed/document.json");
        DocumentImportJobRepository jobs = mock(DocumentImportJobRepository.class);
        when(jobs.selectOne(any())).thenReturn(job);
        when(jobs.claimForIndexing(anyString(), anyString(), anyInt())).thenReturn(1);
        when(jobs.renewIndexingLease(anyString(), anyString(), anyInt())).thenReturn(0);
        InMemoryArtifactStore artifacts = new InMemoryArtifactStore();
        byte[] parsed = objectMapper.writeValueAsBytes(parsedResult());
        artifacts.put(job.getParseArtifactObjectKey(), new ByteArrayInputStream(parsed), parsed.length,
                "application/json");
        PipelineImportService pipeline = mock(PipelineImportService.class);
        when(pipeline.execute(any())).thenReturn(PipelineResult.builder()
                .status("FAILED").errorMessage("stale failure").build());

        worker(jobs, artifacts, mock(DocumentParseRouter.class), pipeline)
                .process(job.getJobId());

        verify(jobs).failIndexing(anyString(), anyString(), anyString());
    }

    private DocumentImportJobWorker worker(DocumentImportJobRepository jobs, DocumentArtifactStore artifacts,
                                             DocumentParseRouter router, PipelineImportService pipeline) {
        return worker(jobs, artifacts, router, pipeline, mock(com.enterprise.ai.vector.VectorService.class));
    }

    private DocumentImportJobWorker worker(DocumentImportJobRepository jobs, DocumentArtifactStore artifacts,
                                             DocumentParseRouter router, PipelineImportService pipeline,
                                             com.enterprise.ai.vector.VectorService vectors) {
        when(jobs.lockValidParsing(anyString(), anyString())).thenAnswer(invocation -> {
            var current = jobs.selectOne(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<DocumentImportJob>()
                    .eq(DocumentImportJob::getJobId, invocation.getArgument(0)));
            return current != null && "PARSING".equals(current.getStatus())
                    && java.util.Objects.equals(current.getLeaseOwner(), invocation.getArgument(1))
                    && current.getLeaseUntil() != null && current.getLeaseUntil().isAfter(LocalDateTime.now()) ? current : null;
        });
        DocumentImportJobProperties properties = new DocumentImportJobProperties();
        properties.setRetryInitialDelaySeconds(1);
        properties.setRetryMaxDelaySeconds(10);
        return new DocumentImportJobWorker(jobs, artifacts, router, pipeline, vectors, objectMapper, properties, com.enterprise.ai.support.ArtifactLifecycleTestSupport.transactions());
    }

    private static DocumentImportJobRepository parsingRepository(DocumentImportJob job) {
        DocumentImportJobRepository jobs = mock(DocumentImportJobRepository.class);
        when(jobs.selectOne(any())).thenReturn(job);
        when(jobs.claimForParsing(anyString(), anyString(), anyInt())).thenAnswer(invocation -> {
            job.setStatus(DocumentImportJobStatus.PARSING.name());
            job.setStage("PARSING");
            job.setAttemptCount(job.getAttemptCount() + 1);
            job.setLeaseOwner(invocation.getArgument(1));
            job.setLeaseUntil(LocalDateTime.now().plusMinutes(1));
            return 1;
        });
        when(jobs.finalizeParsing(anyString(), anyString(), anyString(), anyString(), anyString(), any()))
                .thenAnswer(invocation -> {
                    job.setStatus(DocumentImportJobStatus.PARSED.name());
                    job.setStage("PARSED");
                    job.setProviderType(invocation.getArgument(2));
                    job.setProviderVersion(invocation.getArgument(3));
                    job.setParseArtifactObjectKey(invocation.getArgument(4));
                    job.setParsedAt(invocation.getArgument(5));
                    job.setLeaseOwner(null);
                    return 1;
                });
        when(jobs.failParsing(anyString(), anyString(), anyString(), any(), anyString(), anyString()))
                .thenAnswer(invocation -> {
                    job.setStatus(invocation.getArgument(2));
                    job.setStage("PARSING");
                    job.setNextAttemptAt(invocation.getArgument(3));
                    job.setErrorCode(invocation.getArgument(4));
                    job.setErrorMessage(invocation.getArgument(5));
                    job.setLeaseOwner(null);
                    return 1;
                });
        return jobs;
    }

    private static DocumentImportJob queuedJob() {
        DocumentImportJob job = new DocumentImportJob();
        job.setJobId("dij_unit_test");
        job.setFileId("file_unit_test");
        job.setKnowledgeBaseCode("kb_unit_test"); job.setVectorCollectionName("physical_unit_test"); job.setKnowledgeBaseId(7L);
        job.setFileName("contract.pdf");
        job.setFileType("pdf");
        job.setContentType("application/pdf");
        job.setFileSize(6L);
        job.setSourceObjectKey("jobs/job-1/source/contract.pdf");
        job.setSourceSha256("a".repeat(64));
        job.setProviderType(DocumentProviderType.DOCLING.name());
        job.setStatus(DocumentImportJobStatus.QUEUED.name());
        job.setStage("QUEUED");
        job.setChunkStrategy("fixed_length");
        job.setChunkSize(500);
        job.setChunkOverlap(50);
        job.setExtraParamsJson("{}");
        job.setAutoCommit(0);
        job.setAttemptCount(0);
        job.setMaxAttempts(3);
        return job;
    }

    private static DocumentParseResult parsedResult() {
        return DocumentParseResult.builder()
                .providerType(DocumentProviderType.DOCLING)
                .providerVersion("1.30.0")
                .format(DocumentFormat.PDF)
                .normalizedText("Docling text")
                .elements(List.of(ParsedDocumentElement.builder()
                        .order(0)
                        .type("TEXT")
                        .text("Docling text")
                        .sectionPath("Heading")
                        .build()))
                .build();
    }

    private static final class InMemoryArtifactStore implements DocumentArtifactStore {

        private final Map<String, byte[]> objects = new LinkedHashMap<>();

        @Override
        public void put(String objectKey, InputStream content, long contentLength, String contentType) {
            try {
                objects.put(objectKey, content.readAllBytes());
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }

        @Override
        public InputStream open(String objectKey) {
            byte[] bytes = objects.get(objectKey);
            if (bytes == null) {
                throw new IllegalArgumentException("missing artifact: " + objectKey);
            }
            return new ByteArrayInputStream(bytes);
        }

        @Override
        public void retire(String objectKey) {
            objects.remove(objectKey);
        }

        @Override
        public void retain(String objectKey) {
            if (!objects.containsKey(objectKey)) throw new IllegalStateException("Missing test artifact");
        }

        boolean contains(String objectKey) {
            return objects.containsKey(objectKey);
        }

        byte[] bytes(String objectKey) {
            return objects.get(objectKey);
        }
    }
}
