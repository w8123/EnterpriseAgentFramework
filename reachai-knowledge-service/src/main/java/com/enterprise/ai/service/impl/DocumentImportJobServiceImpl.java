package com.enterprise.ai.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.enterprise.ai.domain.dto.ChunkPreviewResponse;
import com.enterprise.ai.domain.dto.DocumentImportAccessContext;
import com.enterprise.ai.domain.dto.DocumentImportJobResponse;
import com.enterprise.ai.domain.dto.DocumentImportResourceScope;
import com.enterprise.ai.domain.entity.DocumentImportJob;
import com.enterprise.ai.domain.entity.FileInfo;
import com.enterprise.ai.domain.entity.KnowledgeBase;
import com.enterprise.ai.pipeline.PipelineContext;
import com.enterprise.ai.pipeline.document.DocumentChunkSource;
import com.enterprise.ai.pipeline.document.DocumentFormat;
import com.enterprise.ai.pipeline.document.DocumentParseRequest;
import com.enterprise.ai.pipeline.document.DocumentParseResult;
import com.enterprise.ai.pipeline.document.DocumentParseRouter;
import com.enterprise.ai.pipeline.document.artifact.DocumentArtifactStore;
import com.enterprise.ai.pipeline.document.job.DocumentImportJobProperties;
import com.enterprise.ai.pipeline.document.job.DocumentImportJobStatus;
import com.enterprise.ai.pipeline.document.job.DocumentImportJobWorker;
import com.enterprise.ai.pipeline.step.ChunkStep;
import com.enterprise.ai.pipeline.step.TextCleanStep;
import com.enterprise.ai.repository.DocumentImportJobRepository;
import com.enterprise.ai.repository.FileInfoRepository;
import com.enterprise.ai.repository.KnowledgeBaseRepository;
import com.enterprise.ai.service.DocumentImportJobService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Uploads the original exactly once, then drives parse/index work from durable
 * object storage.  A retry never selects a different parser provider.
 */
@Slf4j
@Service
public class DocumentImportJobServiceImpl implements DocumentImportJobService {

    private static final Set<String> CHUNK_STRATEGIES = Set.of("fixed_length", "paragraph", "semantic");
    private static final int MIN_CHUNK_SIZE = 100;
    private static final int MAX_CHUNK_SIZE = 4_000;
    private static final int MAX_CHUNK_OVERLAP = 1_000;

    private final DocumentImportJobRepository jobRepository;
    private final KnowledgeBaseRepository knowledgeBaseRepository;
    private final FileInfoRepository fileInfoRepository;
    private final DocumentArtifactStore artifactStore;
    private final DocumentParseRouter documentParseRouter;
    private final ObjectMapper objectMapper;
    private final DocumentImportJobProperties properties;
    private final DocumentImportJobWorker worker;
    private final TextCleanStep textCleanStep;
    private final ChunkStep chunkStep;

    private final TaskExecutor taskExecutor;

    public DocumentImportJobServiceImpl(DocumentImportJobRepository jobRepository,
                                        KnowledgeBaseRepository knowledgeBaseRepository,
                                        FileInfoRepository fileInfoRepository,
                                        DocumentArtifactStore artifactStore,
                                        DocumentParseRouter documentParseRouter,
                                        ObjectMapper objectMapper,
                                        DocumentImportJobProperties properties,
                                        DocumentImportJobWorker worker,
                                        TextCleanStep textCleanStep,
                                        ChunkStep chunkStep,
                                        @Qualifier("documentImportTaskExecutor") TaskExecutor taskExecutor) {
        this.jobRepository = jobRepository;
        this.knowledgeBaseRepository = knowledgeBaseRepository;
        this.fileInfoRepository = fileInfoRepository;
        this.artifactStore = artifactStore;
        this.documentParseRouter = documentParseRouter;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.worker = worker;
        this.textCleanStep = textCleanStep;
        this.chunkStep = chunkStep;
        this.taskExecutor = taskExecutor;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public DocumentImportJobResponse submit(MultipartFile file, String knowledgeBaseCode,
                                            String chunkStrategy, Integer chunkSize, Integer chunkOverlap,
                                            Map<String, Object> extraParams, boolean autoCommit,
                                            DocumentImportAccessContext accessContext) {
        return submitInternal(file, knowledgeBaseCode, null, chunkStrategy, chunkSize, chunkOverlap,
                extraParams, autoCommit, accessContext);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public DocumentImportJobResponse submitWithFileId(MultipartFile file, String knowledgeBaseCode, String fileId,
                                                      String chunkStrategy, Integer chunkSize, Integer chunkOverlap,
                                                      Map<String, Object> extraParams, boolean autoCommit,
                                                      DocumentImportAccessContext accessContext) {
        if (fileId == null || !fileId.matches("[A-Za-z0-9._-]{1,128}")) {
            throw new IllegalArgumentException("fileId 只能包含字母、数字、点、下划线或短横线，长度不超过 128");
        }
        Long existing = fileInfoRepository.selectCount(
                new LambdaQueryWrapper<FileInfo>().eq(FileInfo::getFileId, fileId));
        if (existing != null && existing > 0) {
            throw new IllegalStateException("fileId 已存在: " + fileId);
        }
        return submitInternal(file, knowledgeBaseCode, fileId, chunkStrategy, chunkSize, chunkOverlap,
                extraParams, autoCommit, accessContext);
    }

    private DocumentImportJobResponse submitInternal(MultipartFile file, String knowledgeBaseCode, String requestedFileId,
                                                     String chunkStrategy, Integer chunkSize, Integer chunkOverlap,
                                                     Map<String, Object> extraParams, boolean autoCommit,
                                                     DocumentImportAccessContext accessContext) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("上传文件不能为空");
        }
        KnowledgeBase knowledgeBase = requireKnowledgeBase(knowledgeBaseCode);
        DocumentImportAccessContext verifiedAccess = requireAccessContext(accessContext);
        requireScopeAssertion(knowledgeBase, verifiedAccess);
        String normalizedChunkStrategy = normalizeChunkStrategy(chunkStrategy);
        int normalizedChunkSize = normalizeChunkSize(chunkSize);
        int normalizedChunkOverlap = normalizeChunkOverlap(chunkOverlap, normalizedChunkSize);
        DocumentParseRequest parseRequest = DocumentParseRequest.fromMultipartFile(file);
        DocumentFormat format = documentParseRouter.detect(parseRequest);
        String jobId = "dij_" + UUID.randomUUID().toString().replace("-", "");
        String fileId = requestedFileId == null ? "file_" + UUID.randomUUID().toString().replace("-", "") : requestedFileId;
        String sourceObjectKey = sourceObjectKey(jobId, file.getOriginalFilename());
        String sourceSha256 = sha256(file);

        try (InputStream input = file.getInputStream()) {
            artifactStore.put(sourceObjectKey, input, file.getSize(), normalizeContentType(file.getContentType()));
        } catch (IOException e) {
            throw new IllegalStateException("无法读取上传文件", e);
        }

        DocumentImportJob job = baseJob(jobId, fileId, knowledgeBase, file.getOriginalFilename(), format,
                file.getContentType(), file.getSize(), sourceObjectKey, sourceSha256,
                normalizedChunkStrategy, normalizedChunkSize, normalizedChunkOverlap, extraParams, autoCommit,
                verifiedAccess);
        try {
            jobRepository.insert(job);
        } catch (RuntimeException e) {
            deleteArtifactQuietly(sourceObjectKey);
            throw e;
        }
        deleteArtifactOnRollback(sourceObjectKey);
        dispatchAfterCommit(jobId);
        return toResponse(job, null);
    }

    @Override
    public DocumentImportJobResponse get(String jobId, boolean includePreview,
                                         DocumentImportAccessContext accessContext) {
        DocumentImportJob job = requireJob(jobId, accessContext);
        ChunkPreviewResponse preview = includePreview && job.getParseArtifactObjectKey() != null
                ? preview(job)
                : null;
        return toResponse(job, preview);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public DocumentImportJobResponse commit(String jobId, DocumentImportAccessContext accessContext) {
        DocumentImportJob job = requireJob(jobId, accessContext);
        if (!DocumentImportJobStatus.PARSED.name().equals(job.getStatus())) {
            throw new IllegalStateException("只有 PARSED 状态的任务可以正式入库，当前状态: " + job.getStatus());
        }
        job.setAutoCommit(1);
        jobRepository.updateById(job);
        dispatchAfterCommit(jobId);
        return toResponse(job, null);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public DocumentImportJobResponse retry(String jobId, DocumentImportAccessContext accessContext) {
        DocumentImportJob job = requireJob(jobId, accessContext);
        if (!DocumentImportJobStatus.FAILED.name().equals(job.getStatus())
                && !DocumentImportJobStatus.RETRY_WAIT.name().equals(job.getStatus())) {
            throw new IllegalStateException("只有失败任务可以重试，当前状态: " + job.getStatus());
        }
        boolean hasParseArtifact = job.getParseArtifactObjectKey() != null && !job.getParseArtifactObjectKey().isBlank();
        job.setStatus(hasParseArtifact ? DocumentImportJobStatus.PARSED.name() : DocumentImportJobStatus.QUEUED.name());
        job.setStage(hasParseArtifact ? "PARSED" : "PARSING");
        job.setNextAttemptAt(null);
        job.setLeaseOwner(null);
        job.setLeaseUntil(null);
        job.setErrorCode(null);
        job.setErrorMessage(null);
        if (!hasParseArtifact) {
            job.setAttemptCount(0);
        }
        jobRepository.updateById(job);
        dispatchAfterCommit(jobId);
        return toResponse(job, null);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public DocumentImportJobResponse reparse(String fileId, DocumentImportAccessContext accessContext) {
        DocumentImportAccessContext verifiedAccess = requireAccessContext(accessContext);
        FileInfo oldFile = fileInfoRepository.selectOne(
                new LambdaQueryWrapper<FileInfo>().eq(FileInfo::getFileId, fileId));
        if (oldFile == null) {
            throw new IllegalArgumentException("文件不存在: " + fileId);
        }
        if (oldFile.getSourceObjectKey() == null || oldFile.getSourceObjectKey().isBlank()) {
            throw new IllegalStateException("该文件没有保存原件，无法使用当前 Provider 重新解析");
        }
        KnowledgeBase knowledgeBase = knowledgeBaseRepository.selectById(oldFile.getKnowledgeBaseId());
        if (knowledgeBase == null) {
            throw new IllegalStateException("文件所属知识库不存在");
        }
        requireScopeAssertion(knowledgeBase, verifiedAccess);

        DocumentFormat format = detectStoredSource(oldFile);
        long sourceSize = oldFile.getFileSize() == null ? 0L : oldFile.getFileSize();
        if (sourceSize <= 0) {
            throw new IllegalStateException("保存的原件缺少有效文件大小，无法安全重新解析");
        }
        String jobId = "dij_" + UUID.randomUUID().toString().replace("-", "");
        String sourceObjectKey = sourceObjectKey(jobId, oldFile.getFileName());
        try (InputStream input = artifactStore.open(oldFile.getSourceObjectKey())) {
            artifactStore.put(sourceObjectKey, input, sourceSize,
                    normalizeContentType(oldFile.getSourceContentType()));
        } catch (IOException e) {
            throw new IllegalStateException("无法读取保存的原件", e);
        }

        DocumentImportJob job = baseJob(jobId,
                "file_" + UUID.randomUUID().toString().replace("-", ""),
                knowledgeBase, oldFile.getFileName(), format, oldFile.getSourceContentType(), oldFile.getFileSize(),
                sourceObjectKey, oldFile.getSourceSha256(), mapSplitType(knowledgeBase.getSplitType()),
                normalizeChunkSize(knowledgeBase.getChunkSize()),
                normalizeChunkOverlap(knowledgeBase.getChunkOverlap(), normalizeChunkSize(knowledgeBase.getChunkSize())),
                Map.of(), true, verifiedAccess);
        job.setReplaceFileId(fileId);
        try {
            jobRepository.insert(job);
        } catch (RuntimeException e) {
            deleteArtifactQuietly(sourceObjectKey);
            throw e;
        }
        deleteArtifactOnRollback(sourceObjectKey);
        dispatchAfterCommit(jobId);
        return toResponse(job, null);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void cancel(String jobId, DocumentImportAccessContext accessContext) {
        DocumentImportJob job = requireJob(jobId, accessContext);
        DocumentImportJobStatus status = DocumentImportJobStatus.valueOf(job.getStatus());
        if (status.isTerminal()) {
            throw new IllegalStateException("终态任务不能取消: " + job.getStatus());
        }
        if (status == DocumentImportJobStatus.INDEXING) {
            throw new IllegalStateException("任务已进入索引阶段，不能安全取消；请等待完成后删除文件");
        }
        job.setStatus(DocumentImportJobStatus.CANCELLED.name());
        job.setStage("CANCELLED");
        job.setLeaseOwner(null);
        job.setLeaseUntil(null);
        jobRepository.updateById(job);
        deleteJobArtifactsAfterCommit(job);
    }

    @Override
    public DocumentImportResourceScope describeKnowledgeBaseScope(String knowledgeBaseCode) {
        return resourceScope(requireKnowledgeBase(knowledgeBaseCode));
    }

    @Override
    public DocumentImportResourceScope describeJobScope(String jobId,
                                                        DocumentImportAccessContext accessContext) {
        DocumentImportJob job = requireJob(jobId, accessContext);
        return DocumentImportResourceScope.builder()
                .workspaceId(job.getWorkspaceId())
                .projectCode(job.getProjectCode())
                .scope(job.getResourceScope())
                .build();
    }

    @Override
    public DocumentImportResourceScope describeFileScope(String fileId) {
        FileInfo file = fileInfoRepository.selectOne(
                new LambdaQueryWrapper<FileInfo>().eq(FileInfo::getFileId, fileId));
        if (file == null) {
            throw new IllegalArgumentException("文件不存在: " + fileId);
        }
        KnowledgeBase knowledgeBase = knowledgeBaseRepository.selectById(file.getKnowledgeBaseId());
        if (knowledgeBase == null) {
            throw new IllegalStateException("文件所属知识库不存在");
        }
        return resourceScope(knowledgeBase);
    }

    @Override
    public void dispatch(String jobId) {
        if (!properties.isEnabled()) {
            return;
        }
        try {
            taskExecutor.execute(() -> worker.process(jobId));
        } catch (RuntimeException e) {
            log.warn("文档导入任务无法排队: jobId={}", jobId, e);
        }
    }

    @Override
    public void dispatchPending() {
        if (!properties.isEnabled()) {
            return;
        }
        int recoveredParse = jobRepository.recoverExpiredParsingLeases();
        int failedIndex = jobRepository.failExpiredIndexingLeases();
        if (recoveredParse > 0 || failedIndex > 0) {
            log.warn("文档导入任务租约恢复: parse={}, indexFailed={}", recoveredParse, failedIndex);
        }
        List<String> jobIds = new ArrayList<>(jobRepository.findPendingParseJobIds(properties.getDispatchBatchSize()));
        jobIds.addAll(jobRepository.findPendingAutoCommitJobIds(properties.getDispatchBatchSize()));
        jobIds.stream().distinct().forEach(this::dispatch);
    }

    private DocumentImportJob baseJob(String jobId, String fileId, KnowledgeBase knowledgeBase, String fileName,
                                      DocumentFormat format, String contentType, Long fileSize, String sourceObjectKey,
                                      String sourceSha256, String chunkStrategy, Integer chunkSize, Integer chunkOverlap,
                                      Map<String, Object> extraParams, boolean autoCommit,
                                      DocumentImportAccessContext accessContext) {
        DocumentImportJob job = new DocumentImportJob();
        job.setJobId(jobId);
        job.setFileId(fileId);
        job.setKnowledgeBaseId(knowledgeBase.getId());
        job.setKnowledgeBaseCode(knowledgeBase.getCode());
        job.setTenantId(accessContext.tenantId().trim());
        job.setCreatedByActorId(accessContext.actorId().trim());
        job.setWorkspaceId(normalizeWorkspaceId(knowledgeBase.getWorkspaceId()));
        job.setProjectCode(normalizeProjectCode(knowledgeBase.getProjectCode()));
        job.setResourceScope(normalizeResourceScope(knowledgeBase.getScope()));
        job.setFileName(fileName);
        job.setFileType(format.getExtension());
        job.setContentType(normalizeContentType(contentType));
        job.setFileSize(fileSize == null ? 0L : fileSize);
        job.setSourceObjectKey(sourceObjectKey);
        job.setSourceSha256(sourceSha256);
        job.setProviderType(format.getProviderType().name());
        job.setStatus(DocumentImportJobStatus.QUEUED.name());
        job.setStage("QUEUED");
        job.setChunkStrategy(chunkStrategy);
        job.setChunkSize(chunkSize);
        job.setChunkOverlap(chunkOverlap);
        job.setExtraParamsJson(writeExtraParams(extraParams));
        job.setAutoCommit(autoCommit ? 1 : 0);
        job.setAttemptCount(0);
        job.setMaxAttempts(properties.getMaxAttempts());
        return job;
    }

    private DocumentFormat detectStoredSource(FileInfo file) {
        DocumentParseRequest request = DocumentParseRequest.builder()
                .fileName(file.getFileName())
                .declaredContentType(file.getSourceContentType())
                .fileSize(file.getFileSize() == null ? 0L : file.getFileSize())
                .content(() -> artifactStore.open(file.getSourceObjectKey()))
                .build();
        return documentParseRouter.detect(request);
    }

    private ChunkPreviewResponse preview(DocumentImportJob job) {
        DocumentParseResult result;
        try (InputStream input = artifactStore.open(job.getParseArtifactObjectKey())) {
            result = objectMapper.readValue(input, DocumentParseResult.class);
        } catch (IOException e) {
            throw new IllegalStateException("无法读取已保存的解析结果", e);
        }
        // Preview must run the exact same clean/chunk stages as formal indexing.
        // In particular, Docling elements are chunked independently to preserve
        // their source anchors, which a flat split of normalizedText would lose.
        PipelineContext context = new PipelineContext();
        context.setFileId(job.getFileId());
        context.setFileName(job.getFileName());
        context.setRawText(result.getNormalizedText());
        context.setParsedDocument(result);
        context.setChunkStrategy(job.getChunkStrategy());
        context.setChunkSize(job.getChunkSize() == null ? 500 : job.getChunkSize());
        context.setChunkOverlap(job.getChunkOverlap() == null ? 50 : job.getChunkOverlap());
        context.setExtraParams(readExtraParams(job.getExtraParamsJson()));
        textCleanStep.process(context);
        chunkStep.process(context);

        List<String> chunks = context.getChunks();
        List<DocumentChunkSource> sources = context.getChunkSources();
        List<ChunkPreviewResponse.ChunkItem> items = new ArrayList<>();
        for (int index = 0; index < chunks.size(); index++) {
            String chunk = chunks.get(index);
            DocumentChunkSource source = index < sources.size() ? sources.get(index) : null;
            items.add(new ChunkPreviewResponse.ChunkItem(
                    index,
                    chunk,
                    chunk.length(),
                    source == null ? null : source.getElementType(),
                    source == null ? null : source.getSectionPath(),
                    source == null ? null : writeSourceLocator(source)));
        }
        return new ChunkPreviewResponse(job.getFileName(), job.getChunkStrategy(), job.getChunkSize(),
                job.getChunkOverlap(), items.size(), items);
    }

    private DocumentImportJob requireJob(String jobId, DocumentImportAccessContext accessContext) {
        DocumentImportAccessContext verifiedAccess = requireAccessContext(accessContext).identityOnly();
        DocumentImportJob job = jobRepository.selectOne(
                new LambdaQueryWrapper<DocumentImportJob>().eq(DocumentImportJob::getJobId, jobId));
        if (job == null
                || !Objects.equals(job.getTenantId(), verifiedAccess.tenantId())
                || !Objects.equals(job.getCreatedByActorId(), verifiedAccess.actorId())) {
            throw new IllegalArgumentException("文档导入任务不存在: " + jobId);
        }
        return job;
    }

    private KnowledgeBase requireKnowledgeBase(String knowledgeBaseCode) {
        if (knowledgeBaseCode == null || knowledgeBaseCode.isBlank()) {
            throw new IllegalArgumentException("知识库编码不能为空");
        }
        KnowledgeBase knowledgeBase = knowledgeBaseRepository.selectOne(
                new LambdaQueryWrapper<KnowledgeBase>().eq(KnowledgeBase::getCode, knowledgeBaseCode));
        if (knowledgeBase == null) {
            throw new IllegalArgumentException("知识库不存在: " + knowledgeBaseCode);
        }
        return knowledgeBase;
    }

    private DocumentImportAccessContext requireAccessContext(DocumentImportAccessContext accessContext) {
        if (accessContext == null
                || invalidIdentity(accessContext.tenantId(), 96)
                || invalidIdentity(accessContext.actorId(), 128)) {
            throw new IllegalArgumentException("文档导入需要已验证的 Control 身份");
        }
        return new DocumentImportAccessContext(
                accessContext.tenantId().trim(),
                accessContext.actorId().trim(),
                accessContext.workspaceId(),
                accessContext.projectCode(),
                accessContext.scope());
    }

    private void requireScopeAssertion(KnowledgeBase knowledgeBase, DocumentImportAccessContext accessContext) {
        DocumentImportResourceScope actual = resourceScope(knowledgeBase);
        if (accessContext.workspaceId() == null || accessContext.workspaceId().isBlank()
                || accessContext.scope() == null || accessContext.scope().isBlank()
                || ("PROJECT".equals(actual.getScope())
                    && (accessContext.projectCode() == null || accessContext.projectCode().isBlank()))) {
            throw new IllegalArgumentException("知识库授权作用域不能为空");
        }
        String assertedScope = normalizeResourceScope(accessContext.scope());
        String assertedWorkspace = normalizeWorkspaceId(accessContext.workspaceId());
        String assertedProject = normalizeProjectCode(accessContext.projectCode());
        if (!Objects.equals(actual.getScope(), assertedScope)
                || !Objects.equals(actual.getWorkspaceId(), assertedWorkspace)
                || !Objects.equals(actual.getProjectCode(), assertedProject)) {
            throw new IllegalArgumentException("知识库授权作用域已变化，请刷新后重试");
        }
    }

    private static DocumentImportResourceScope resourceScope(KnowledgeBase knowledgeBase) {
        return DocumentImportResourceScope.builder()
                .workspaceId(normalizeWorkspaceId(knowledgeBase.getWorkspaceId()))
                .projectCode(normalizeProjectCode(knowledgeBase.getProjectCode()))
                .scope(normalizeResourceScope(knowledgeBase.getScope()))
                .build();
    }

    private static boolean invalidIdentity(String value, int maxLength) {
        return value == null || value.isBlank() || value.length() > maxLength
                || value.chars().anyMatch(Character::isISOControl);
    }

    private static String normalizeChunkStrategy(String value) {
        String normalized = defaultString(value, "fixed_length").trim().toLowerCase(Locale.ROOT);
        if (!CHUNK_STRATEGIES.contains(normalized)) {
            throw new IllegalArgumentException("chunkStrategy 仅支持 fixed_length、paragraph 或 semantic");
        }
        return normalized;
    }

    private static int normalizeChunkSize(Integer value) {
        int normalized = value == null ? 500 : value;
        if (normalized < MIN_CHUNK_SIZE || normalized > MAX_CHUNK_SIZE) {
            throw new IllegalArgumentException("chunkSize 必须在 100 到 4000 之间");
        }
        return normalized;
    }

    private static int normalizeChunkOverlap(Integer value, int chunkSize) {
        int normalized = value == null ? 50 : value;
        if (normalized < 0 || normalized > MAX_CHUNK_OVERLAP || normalized >= chunkSize) {
            throw new IllegalArgumentException("chunkOverlap 必须在 0 到 1000 之间且小于 chunkSize");
        }
        return normalized;
    }

    private static String normalizeWorkspaceId(String value) {
        return value == null || value.isBlank() ? "default" : value.trim();
    }

    private static String normalizeProjectCode(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String normalizeResourceScope(String value) {
        String normalized = value == null || value.isBlank()
                ? "WORKSPACE"
                : value.trim().toUpperCase(Locale.ROOT);
        if (!Set.of("SHARED", "WORKSPACE", "PROJECT").contains(normalized)) {
            throw new IllegalArgumentException("知识库 scope 仅支持 SHARED、WORKSPACE 或 PROJECT");
        }
        return normalized;
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

    private String writeExtraParams(Map<String, Object> extraParams) {
        try {
            return objectMapper.writeValueAsString(extraParams == null ? Map.of() : extraParams);
        } catch (Exception e) {
            throw new IllegalArgumentException("extraParams 必须可序列化为 JSON", e);
        }
    }

    private String writeSourceLocator(DocumentChunkSource source) {
        if (source.getSourceLocator() == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(source.getSourceLocator());
        } catch (IOException e) {
            throw new IllegalStateException("无法序列化预览来源定位", e);
        }
    }

    private String sha256(MultipartFile file) {
        try (InputStream input = file.getInputStream()) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8_192];
            for (int read; (read = input.read(buffer)) >= 0; ) {
                digest.update(buffer, 0, read);
            }
            return java.util.HexFormat.of().formatHex(digest.digest());
        } catch (IOException | NoSuchAlgorithmException e) {
            throw new IllegalStateException("无法计算上传文件摘要", e);
        }
    }

    private void dispatchAfterCommit(String jobId) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    dispatch(jobId);
                }
            });
        } else {
            dispatch(jobId);
        }
    }

    private DocumentImportJobResponse toResponse(DocumentImportJob job, ChunkPreviewResponse preview) {
        return DocumentImportJobResponse.builder()
                .jobId(job.getJobId())
                .fileId(job.getFileId())
                .replaceFileId(job.getReplaceFileId())
                .knowledgeBaseCode(job.getKnowledgeBaseCode())
                .fileName(job.getFileName())
                .fileType(job.getFileType())
                .providerType(job.getProviderType())
                .providerVersion(job.getProviderVersion())
                .status(job.getStatus())
                .stage(job.getStage())
                .attemptCount(job.getAttemptCount())
                .maxAttempts(job.getMaxAttempts())
                .autoCommit(job.getAutoCommit() != null && job.getAutoCommit() == 1)
                .errorCode(job.getErrorCode())
                .errorMessage(job.getErrorMessage())
                .parsedAt(job.getParsedAt())
                .completedAt(job.getCompletedAt())
                .createTime(job.getCreateTime())
                .updateTime(job.getUpdateTime())
                .preview(preview)
                .build();
    }

    private static String sourceObjectKey(String jobId, String fileName) {
        String candidate = fileName == null ? "document" : fileName.replace('\\', '/');
        int slash = candidate.lastIndexOf('/');
        candidate = slash >= 0 ? candidate.substring(slash + 1) : candidate;
        candidate = candidate.replaceAll("[^A-Za-z0-9._-]", "_");
        return "knowledge-document-import/" + jobId + "/source/" + (candidate.isBlank() ? "document" : candidate);
    }

    private static String defaultString(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value;
    }

    private static String normalizeContentType(String contentType) {
        return contentType == null || contentType.isBlank() ? "application/octet-stream" : contentType;
    }

    private static String mapSplitType(String splitType) {
        if (splitType == null || splitType.isBlank()) {
            return "fixed_length";
        }
        return switch (splitType.trim().toUpperCase()) {
            case "PARAGRAPH" -> "paragraph";
            case "SEMANTIC" -> "semantic";
            default -> "fixed_length";
        };
    }

    private void deleteArtifactQuietly(String objectKey) {
        try {
            artifactStore.delete(objectKey);
        } catch (Exception cleanupFailure) {
            log.warn("无法清理未关联的文档工件: {}", objectKey, cleanupFailure);
        }
    }

    private void deleteJobArtifactsAfterCommit(DocumentImportJob job) {
        Runnable cleanup = () -> {
            deleteArtifactQuietly(job.getSourceObjectKey());
            deleteArtifactQuietly(job.getParseArtifactObjectKey());
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    cleanup.run();
                }
            });
        } else {
            cleanup.run();
        }
    }

    private void deleteArtifactOnRollback(String objectKey) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status != STATUS_COMMITTED) {
                    deleteArtifactQuietly(objectKey);
                }
            }
        });
    }
}
