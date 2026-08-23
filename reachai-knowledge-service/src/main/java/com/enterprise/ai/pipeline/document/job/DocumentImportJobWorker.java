package com.enterprise.ai.pipeline.document.job;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.enterprise.ai.domain.dto.PipelineResult;
import com.enterprise.ai.domain.entity.DocumentImportJob;
import com.enterprise.ai.pipeline.PipelineContext;
import com.enterprise.ai.pipeline.document.DocumentParseException;
import com.enterprise.ai.pipeline.document.DocumentParseRequest;
import com.enterprise.ai.pipeline.document.DocumentParseResult;
import com.enterprise.ai.pipeline.document.DocumentParseRouter;
import com.enterprise.ai.pipeline.document.artifact.DocumentArtifactException;
import com.enterprise.ai.pipeline.document.artifact.DocumentArtifactStore;
import com.enterprise.ai.repository.DocumentImportJobRepository;
import com.enterprise.ai.service.KnowledgeService;
import com.enterprise.ai.service.PipelineImportService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

/** Does remote work outside database transactions and only holds short row leases. */
@Slf4j
@Component
@RequiredArgsConstructor
public class DocumentImportJobWorker {

    private final DocumentImportJobRepository jobRepository;
    private final DocumentArtifactStore artifactStore;
    private final DocumentParseRouter documentParseRouter;
    private final PipelineImportService pipelineImportService;
    private final KnowledgeService knowledgeService;
    private final ObjectMapper objectMapper;
    private final DocumentImportJobProperties properties;
    private final String workerInstanceId = "knowledge-doc-worker-" + UUID.randomUUID();

    public void process(String jobId) {
        DocumentImportJob job = find(jobId);
        if (job == null) {
            return;
        }
        if (DocumentImportJobStatus.QUEUED.name().equals(job.getStatus())
                || DocumentImportJobStatus.RETRY_WAIT.name().equals(job.getStatus())) {
            String leaseOwner = newLeaseOwner();
            if (jobRepository.claimForParsing(jobId, leaseOwner, properties.getLeaseSeconds()) == 0) {
                return;
            }
            parse(jobId, leaseOwner);
            return;
        }
        if (DocumentImportJobStatus.PARSED.name().equals(job.getStatus()) && isTrue(job.getAutoCommit())) {
            index(jobId, newLeaseOwner());
        }
    }

    private void parse(String jobId, String leaseOwner) {
        DocumentImportJob job = require(jobId);
        String artifactKey = null;
        boolean parsedPersisted = false;
        try {
            DocumentParseRequest request = DocumentParseRequest.builder()
                    .fileName(job.getFileName())
                    .declaredContentType(job.getContentType())
                    .fileSize(job.getFileSize() == null ? 0L : job.getFileSize())
                    .content(() -> artifactStore.open(job.getSourceObjectKey()))
                    .build();
            request.getOptions().put("importId", jobId);
            DocumentParseResult result = documentParseRouter.parse(request);
            int attempt = job.getAttemptCount() == null ? 0 : job.getAttemptCount();
            artifactKey = "knowledge-document-import/" + jobId + "/parsed/attempt-" + attempt + ".json";
            persistParseArtifact(artifactKey, result, jobId);
            parsedPersisted = markParsed(jobId, leaseOwner, result, artifactKey);
            if (!parsedPersisted) {
                deleteArtifactQuietly(artifactKey, jobId);
                return;
            }
            DocumentImportJob afterParse = require(jobId);
            if (DocumentImportJobStatus.PARSED.name().equals(afterParse.getStatus()) && isTrue(afterParse.getAutoCommit())) {
                index(jobId, newLeaseOwner());
            }
        } catch (Exception e) {
            if (!parsedPersisted && artifactKey != null) {
                deleteArtifactQuietly(artifactKey, jobId);
            }
            markParseFailure(jobId, leaseOwner, e);
        }
    }

    private void index(String jobId, String leaseOwner) {
        if (jobRepository.claimForIndexing(jobId, leaseOwner, properties.getLeaseSeconds()) == 0) {
            return;
        }
        DocumentImportJob job = require(jobId);
        try {
            DocumentParseResult parsedDocument = loadParseArtifact(job);
            PipelineContext context = new PipelineContext();
            context.setFileId(job.getFileId());
            context.setFileName(job.getFileName());
            context.setKnowledgeBaseCode(job.getKnowledgeBaseCode());
            context.setChunkStrategy(job.getChunkStrategy());
            context.setChunkSize(job.getChunkSize() == null ? 500 : job.getChunkSize());
            context.setChunkOverlap(job.getChunkOverlap() == null ? 50 : job.getChunkOverlap());
            context.setParsedDocument(parsedDocument);
            context.setRawText(parsedDocument.getNormalizedText());
            context.setSourceFileSize(job.getFileSize());
            context.setSourceContentType(job.getContentType());
            context.setSourceObjectKey(job.getSourceObjectKey());
            context.setSourceSha256(job.getSourceSha256());
            context.setParseArtifactObjectKey(job.getParseArtifactObjectKey());
            context.setImportJobId(job.getJobId());
            context.setExtraParams(readExtraParams(job.getExtraParamsJson()));

            PipelineResult result = pipelineImportService.execute(context);
            if (!"SUCCESS".equals(result.getStatus())) {
                throw new IllegalStateException(result.getErrorMessage() == null
                        ? "知识入库流水线失败" : result.getErrorMessage());
            }
            boolean completed = markCompleted(jobId, leaseOwner);
            if (completed && job.getReplaceFileId() != null && !job.getReplaceFileId().isBlank()) {
                try {
                    knowledgeService.deleteFileById(job.getReplaceFileId());
                } catch (Exception e) {
                    log.error("替换导入已完成，但无法删除旧文件: oldFileId={}, jobId={}",
                            job.getReplaceFileId(), jobId, e);
                }
            }
        } catch (Exception e) {
            if (jobRepository.renewIndexingLease(jobId, leaseOwner, properties.getLeaseSeconds()) == 0) {
                log.warn("忽略已失去租约的索引 worker 结果: jobId={}, leaseOwner={}", jobId, leaseOwner);
                return;
            }
            compensateIndexFailure(job, e);
            markIndexFailure(jobId, leaseOwner, e);
        }
    }

    private void compensateIndexFailure(DocumentImportJob job, Exception originalFailure) {
        try {
            knowledgeService.cleanupImportIndexData(job.getKnowledgeBaseCode(), job.getFileId(), job.getJobId());
        } catch (Exception cleanupFailure) {
            originalFailure.addSuppressed(cleanupFailure);
            log.error("文档索引失败补偿异常: jobId={}", job.getJobId(), cleanupFailure);
        }
    }

    private boolean markParsed(String jobId, String leaseOwner,
                               DocumentParseResult result, String artifactKey) {
        return jobRepository.finalizeParsing(
                jobId,
                leaseOwner,
                result.getProviderType().name(),
                result.getProviderVersion(),
                artifactKey,
                LocalDateTime.now()) == 1;
    }

    private boolean markCompleted(String jobId, String leaseOwner) {
        return jobRepository.finalizeIndexing(jobId, leaseOwner, LocalDateTime.now()) == 1;
    }

    private void markParseFailure(String jobId, String leaseOwner, Exception failure) {
        DocumentImportJob current = require(jobId);
        if (!DocumentImportJobStatus.PARSING.name().equals(current.getStatus())) {
            return;
        }
        DocumentParseException parseException = findParseException(failure);
        boolean retryable = parseException != null ? parseException.isRetryable() : failure instanceof DocumentArtifactException;
        int attempts = current.getAttemptCount() == null ? 0 : current.getAttemptCount();
        int maxAttempts = current.getMaxAttempts() == null ? properties.getMaxAttempts() : current.getMaxAttempts();
        String errorCode = parseException == null ? "PARSE_INTERNAL_ERROR" : parseException.getErrorCode().name();
        String errorMessage = sanitize(failure.getMessage());
        String status;
        LocalDateTime nextAttemptAt;
        if (retryable && attempts < maxAttempts) {
            status = DocumentImportJobStatus.RETRY_WAIT.name();
            nextAttemptAt = LocalDateTime.now().plusSeconds(retryDelaySeconds(attempts));
        } else {
            status = DocumentImportJobStatus.FAILED.name();
            nextAttemptAt = null;
        }
        int updated = jobRepository.failParsing(
                jobId, leaseOwner, status, nextAttemptAt, errorCode, errorMessage);
        if (updated == 1) {
            log.warn("文档解析失败: jobId={}, code={}, retryable={}, attempts={}/{}",
                    jobId, errorCode, retryable, attempts, maxAttempts, failure);
        } else {
            log.warn("忽略已失去租约的解析失败结果: jobId={}, leaseOwner={}", jobId, leaseOwner);
        }
    }

    private void markIndexFailure(String jobId, String leaseOwner, Exception failure) {
        if (jobRepository.failIndexing(jobId, leaseOwner, sanitize(failure.getMessage())) == 1) {
            log.error("文档索引失败: jobId={}", jobId, failure);
        } else {
            log.warn("索引补偿后任务租约已变化，未覆盖当前状态: jobId={}, leaseOwner={}",
                    jobId, leaseOwner);
        }
    }

    private String newLeaseOwner() {
        return workerInstanceId + "-" + UUID.randomUUID().toString().replace("-", "");
    }

    private DocumentParseResult loadParseArtifact(DocumentImportJob job) {
        if (job.getParseArtifactObjectKey() == null || job.getParseArtifactObjectKey().isBlank()) {
            throw new IllegalStateException("任务没有可用的解析工件");
        }
        try (InputStream input = artifactStore.open(job.getParseArtifactObjectKey())) {
            return objectMapper.readValue(input, DocumentParseResult.class);
        } catch (IOException e) {
            throw new IllegalStateException("无法读取解析工件", e);
        }
    }

    private void persistParseArtifact(String artifactKey, DocumentParseResult result, String jobId) throws IOException {
        Path temporaryFile = Files.createTempFile("reachai-document-parse-", ".json");
        try {
            objectMapper.writeValue(temporaryFile.toFile(), result);
            long contentLength = Files.size(temporaryFile);
            try (InputStream input = Files.newInputStream(temporaryFile)) {
                artifactStore.put(artifactKey, input, contentLength, "application/json");
            }
        } finally {
            try {
                Files.deleteIfExists(temporaryFile);
            } catch (IOException e) {
                log.warn("无法删除文档解析临时文件: jobId={}", jobId, e);
            }
        }
    }

    private Map<String, Object> readExtraParams(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (IOException e) {
            throw new IllegalStateException("任务扩展参数 JSON 已损坏", e);
        }
    }

    private DocumentImportJob require(String jobId) {
        DocumentImportJob job = find(jobId);
        if (job == null) {
            throw new IllegalStateException("文档导入任务不存在: " + jobId);
        }
        return job;
    }

    private DocumentImportJob find(String jobId) {
        return jobRepository.selectOne(new LambdaQueryWrapper<DocumentImportJob>()
                .eq(DocumentImportJob::getJobId, jobId));
    }

    private long retryDelaySeconds(int attempts) {
        long exponential = properties.getRetryInitialDelaySeconds() * (1L << Math.min(10, Math.max(0, attempts - 1)));
        return Math.min(properties.getRetryMaxDelaySeconds(), exponential);
    }

    private static DocumentParseException findParseException(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof DocumentParseException parseException) {
                return parseException;
            }
            current = current.getCause();
        }
        return null;
    }

    private static boolean isTrue(Integer value) {
        return value != null && value == 1;
    }

    private static String sanitize(String message) {
        if (message == null || message.isBlank()) {
            return "未提供错误详情";
        }
        String oneLine = message.replaceAll("[\\r\\n\\t]+", " ").trim();
        return oneLine.length() <= 1_000 ? oneLine : oneLine.substring(0, 1_000);
    }

    private void deleteArtifactQuietly(String objectKey, String jobId) {
        try {
            artifactStore.delete(objectKey);
        } catch (Exception cleanupFailure) {
            log.warn("无法清理已取消/失败任务的解析工件: jobId={}, objectKey={}", jobId, objectKey,
                    cleanupFailure);
        }
    }
}
