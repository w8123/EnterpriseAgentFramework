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
import com.enterprise.ai.service.PipelineImportService;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.Map;
import java.util.UUID;

/** Does remote work outside database transactions and only holds short row leases. */
@Slf4j
@Component
public class DocumentImportJobWorker {

    private final DocumentImportJobRepository jobRepository;
    private final DocumentArtifactStore artifactStore;
    private final DocumentParseRouter documentParseRouter;
    private final PipelineImportService pipelineImportService;
    private final com.enterprise.ai.vector.VectorService vectorService;
    private final ObjectMapper objectMapper;
    private final DocumentImportJobProperties properties;
    private final TransactionTemplate metadata;
    private final String workerInstanceId = "knowledge-doc-worker-" + UUID.randomUUID();

    public DocumentImportJobWorker(DocumentImportJobRepository jobRepository, DocumentArtifactStore artifactStore,
                                    DocumentParseRouter documentParseRouter, PipelineImportService pipelineImportService,
                                    com.enterprise.ai.vector.VectorService vectorService, ObjectMapper objectMapper,
                                    DocumentImportJobProperties properties, PlatformTransactionManager manager) {
        this.jobRepository = jobRepository;
        this.artifactStore = artifactStore;
        this.documentParseRouter = documentParseRouter;
        this.pipelineImportService = pipelineImportService;
        this.vectorService = vectorService;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.metadata = new TransactionTemplate(manager);
    }

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
            // Manual retries reset attempt_count; only the execution lease is unique across retries.
            artifactKey = "knowledge-document-import/" + jobId + "/parsed/" + leaseOwner + ".json";
            persistParseArtifact(artifactKey, result);
            parsedPersisted = markParsed(jobId, leaseOwner, result, artifactKey);
            if (!parsedPersisted) {
                retireArtifactQuietly(artifactKey, jobId);
                return;
            }
            DocumentImportJob afterParse = require(jobId);
            if (DocumentImportJobStatus.PARSED.name().equals(afterParse.getStatus()) && isTrue(afterParse.getAutoCommit())) {
                index(jobId, newLeaseOwner());
            }
        } catch (Exception e) {
            if (!parsedPersisted && artifactKey != null) {
                retireArtifactQuietly(artifactKey, jobId);
            }
            markParseFailure(jobId, leaseOwner, e);
        }
    }

    private void index(String jobId, String leaseOwner) {
        if (jobRepository.claimForIndexing(jobId, leaseOwner, properties.getLeaseSeconds()) == 0) {
            return;
        }
        DocumentImportJob job = require(jobId);
        PipelineContext context = new PipelineContext();
        try {
            DocumentParseResult parsedDocument = loadParseArtifact(job);
            context.setFileId(job.getFileId());
            context.setFileName(job.getFileName());
            context.setKnowledgeBaseCode(job.getKnowledgeBaseCode());
            context.setKnowledgeBaseId(job.getKnowledgeBaseId());
            context.setVectorCollectionName(job.getVectorCollectionName());
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
            context.setImportLeaseOwner(leaseOwner);
            context.setExtraParams(readExtraParams(job.getExtraParamsJson()));

            PipelineResult result = pipelineImportService.execute(context);
            if (!"SUCCESS".equals(result.getStatus())) {
                throw new IllegalStateException(result.getErrorMessage() == null
                        ? "知识入库流水线失败" : result.getErrorMessage());
            }
            boolean completed = context.isImportPublished();
            if (!completed) {
                throw new IllegalStateException("索引流水线未提交元数据及完成状态");
            }
        } catch (Exception e) {
            if (context.isImportPublished()) {
                log.error("索引已提交，保留已发布数据: jobId={}", jobId, e);
                return;
            }
            try {
                // This conditional UPDATE serializes with publication, including an uncertain commit.
                if (markIndexFailure(jobId, leaseOwner, e)) {
                    compensateIndexFailure(context, e);
                }
            } catch (Exception statusFailure) {
                log.error("无法确认索引提交结果，保留向量待核对: jobId={}", jobId, statusFailure);
            }
        }
    }

    private void compensateIndexFailure(PipelineContext context, Exception originalFailure) {
        // Metadata failure rolls back its transaction. Only this execution's vectors need compensation.
        for (String vectorId : context.getVectorIds().stream().distinct().toList()) {
            try {
                vectorService.deleteById(context.getVectorCollectionName(), vectorId);
            } catch (Exception cleanupFailure) {
                originalFailure.addSuppressed(cleanupFailure);
                log.error("文档索引向量补偿异常: jobId={}, vectorId={}", context.getImportJobId(), vectorId, cleanupFailure);
            }
        }
    }

    private boolean markParsed(String jobId, String leaseOwner,
                               DocumentParseResult result, String artifactKey) {
        return Boolean.TRUE.equals(metadata.execute(status -> {
            var job = jobRepository.selectOne(new LambdaQueryWrapper<DocumentImportJob>()
                    .eq(DocumentImportJob::getJobId, jobId).last("FOR UPDATE"));
            if (job == null || jobRepository.lockValidParsing(jobId, leaseOwner) == null) {
                return false;
            }
            artifactStore.retain(artifactKey);
            boolean updated = jobRepository.finalizeParsing(jobId, leaseOwner, result.getProviderType().name(),
                    result.getProviderVersion(), artifactKey, LocalDateTime.now()) == 1;
            if (!updated) status.setRollbackOnly();
            return updated;
        }));
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

    private boolean markIndexFailure(String jobId, String leaseOwner, Exception failure) {
        if (jobRepository.failIndexing(jobId, leaseOwner, sanitize(failure.getMessage())) == 1) {
            log.error("文档索引失败: jobId={}", jobId, failure);
            return true;
        } else {
            log.warn("任务已完成或租约已变化，保留向量及当前状态: jobId={}, leaseOwner={}",
                    jobId, leaseOwner);
            return false;
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

    private void persistParseArtifact(String artifactKey, DocumentParseResult result) throws IOException {
        Path temporaryFile = Path.of(System.getProperty("java.io.tmpdir"))
                .resolve("reachai-document-parse-" + UUID.randomUUID().toString().replace("-", "") + ".json");
        FileAttribute<?>[] permissions = Files.getFileStore(temporaryFile.getParent()).supportsFileAttributeView("posix")
                ? new FileAttribute<?>[]{PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"))}
                : new FileAttribute<?>[0];
        // Create and mark for deletion in the same open; retain one handle through serialization and upload.
        try (FileChannel channel = FileChannel.open(temporaryFile, EnumSet.of(StandardOpenOption.CREATE_NEW,
                StandardOpenOption.READ, StandardOpenOption.WRITE, StandardOpenOption.DELETE_ON_CLOSE), permissions)) {
            objectMapper.writer().without(JsonGenerator.Feature.AUTO_CLOSE_TARGET)
                    .writeValue(Channels.newOutputStream(channel), result);
            long contentLength = channel.size();
            channel.position(0);
            try (InputStream input = Channels.newInputStream(channel)) {
                artifactStore.put(artifactKey, input, contentLength, "application/json");
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

    private void retireArtifactQuietly(String objectKey, String jobId) {
        try {
            artifactStore.retire(objectKey);
        } catch (Exception cleanupFailure) {
            log.warn("解析工件退场登记暂未完成: jobId={}, errorType={}", jobId, cleanupFailure.getClass().getSimpleName());
        }
    }
}
