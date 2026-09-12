package com.enterprise.ai.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.enterprise.ai.domain.entity.*;
import com.enterprise.ai.pipeline.document.artifact.DocumentArtifactStore;
import com.enterprise.ai.pipeline.document.job.DocumentIndexExecutionStore;
import com.enterprise.ai.pipeline.document.job.DocumentIndexVectorManifest;
import com.enterprise.ai.repository.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.List;
import java.util.Objects;

/** Database visibility and vector retirement are one transaction; remote cleanup follows commit. */
@Service
public class KnowledgeFileDeletionService {
    private final KnowledgeBaseRepository bases;
    private final FileInfoRepository files;
    private final ChunkRepository chunks;
    private final DocumentImportJobRepository jobs;
    private final DocumentIndexExecutionRepository executions;
    private final DocumentIndexExecutionStore indexStore;
    private final DocumentArtifactStore artifacts;
    private final UserFilePermissionRepository permissions;
    private final KnowledgeQuestionService questions;
    private final KnowledgeTagService tags;
    private final TransactionTemplate transactions;

    public KnowledgeFileDeletionService(KnowledgeBaseRepository bases, FileInfoRepository files,
            ChunkRepository chunks, DocumentImportJobRepository jobs, DocumentIndexExecutionRepository executions,
            DocumentIndexExecutionStore indexStore, DocumentArtifactStore artifacts,
            UserFilePermissionRepository permissions, KnowledgeQuestionService questions, PlatformTransactionManager manager,
            KnowledgeTagService tags) {
        this.bases = bases; this.files = files; this.chunks = chunks; this.jobs = jobs;
        this.executions = executions; this.indexStore = indexStore; this.artifacts = artifacts;
        this.permissions = permissions; this.questions = questions;
        this.tags = tags;
        this.transactions = new TransactionTemplate(manager);
    }

    public void deleteByFileId(String knowledgeBaseCode, String fileId) {
        transactions.executeWithoutResult(status -> {
            var kb = new KnowledgeBaseLookup(bases).requireByCode(knowledgeBaseCode);
            delete(kb, fileId, null, null, null);
        });
    }

    public void deleteFileById(String fileId) {
        transactions.executeWithoutResult(status -> {
            var file = files.selectOne(new LambdaQueryWrapper<FileInfo>().eq(FileInfo::getFileId, fileId));
            if (file == null) throw new IllegalArgumentException("文件不存在: " + fileId);
            var kb = new KnowledgeBaseLookup(bases).requireById(file.getKnowledgeBaseId());
            delete(kb, fileId, file.getId(), file.requireRecordGeneration(), null);
        });
    }

    /** Called before completing the publishing job, within its metadata transaction. */
    public void retireReplacement(DocumentImportJob publishingJob) {
        if (publishingJob.getReplaceFileId() == null) return;
        if (!TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("替换退场必须处于发布事务中");
        if (publishingJob.getReplaceFileId().isBlank() || publishingJob.getReplaceFileRowId() == null
                || publishingJob.getReplaceFileId().equals(publishingJob.getFileId())) {
            throw new IllegalStateException("任务缺少原文件身份，请重新提交解析任务");
        }
        var kb = new KnowledgeBaseLookup(bases).requireById(publishingJob.getKnowledgeBaseId());
        if (!Objects.equals(kb.getCode(), publishingJob.getKnowledgeBaseCode())
                || !Objects.equals(kb.getVectorCollectionName(), publishingJob.getVectorCollectionName())) {
            throw new IllegalStateException("替换任务的原知识库身份已失效");
        }
        delete(kb, publishingJob.getReplaceFileId(), publishingJob.getReplaceFileRowId(), publishingJob.getReplaceFileGeneration(), publishingJob.getJobId());
    }

    private void delete(KnowledgeBase expected, String fileId, Long expectedFileRowId, String expectedFileGeneration, String publishingJobId) {
        if (fileId == null || fileId.isBlank()) throw new IllegalArgumentException("文件ID不能为空");
        var kb = bases.lockById(expected.getId());
        if (kb == null || !Objects.equals(kb.getCode(), expected.getCode())
                || !Objects.equals(kb.getVectorCollectionName(), expected.getVectorCollectionName())) {
            throw new IllegalStateException("原知识库身份已失效");
        }
        // Submission also locks this knowledge base before inserting a job. No older intent can escape this set.
        var relatedJobs = jobs.selectList(new LambdaQueryWrapper<DocumentImportJob>()
                .eq(DocumentImportJob::getKnowledgeBaseId, kb.getId())
                .and(q -> q.eq(DocumentImportJob::getFileId, fileId).or().eq(DocumentImportJob::getReplaceFileId, fileId))
                .orderByAsc(DocumentImportJob::getId).last("FOR UPDATE"));
        var cancelled = relatedJobs.stream().filter(job -> !Objects.equals(job.getJobId(), publishingJobId)
                && !"COMPLETED".equals(job.getStatus()) && !"CANCELLED".equals(job.getStatus())).toList();
        var cancelledIds = cancelled.stream().map(DocumentImportJob::getJobId).toList();
        var oldExecutions = executions.selectList(new LambdaQueryWrapper<DocumentIndexExecution>()
                .eq(DocumentIndexExecution::getKnowledgeBaseId, kb.getId())
                .in(DocumentIndexExecution::getState, "REGISTERED", "PUBLISHED", "RECLAIMING")
                .and(q -> {
                    q.eq(DocumentIndexExecution::getFileId, fileId);
                    if (!cancelledIds.isEmpty()) q.or().in(DocumentIndexExecution::getJobId, cancelledIds);
                }).orderByAsc(DocumentIndexExecution::getLeaseOwner).last("FOR UPDATE"));
        var file = files.lockByFileId(fileId);
        if (file != null && !Objects.equals(file.getKnowledgeBaseId(), kb.getId())) throw new IllegalArgumentException("文件不属于指定知识库");
        if (expectedFileRowId != null && (file == null || !file.hasRecordIdentity(expectedFileRowId, expectedFileGeneration))) {
            throw new IllegalStateException("原文件已删除或重建，拒绝删除新文件");
        }
        var references = chunks.selectList(new LambdaQueryWrapper<Chunk>().eq(Chunk::getKnowledgeBaseId, kb.getId())
                .eq(Chunk::getFileId, fileId).orderByAsc(Chunk::getId).last("FOR UPDATE"));
        retireExecutionsAndJobs(kb, references, oldExecutions, cancelled);
        questions.unlinkFileChunks(kb.getId(), fileId);
        tags.retireFile(kb.getId(), fileId);
        chunks.delete(new LambdaQueryWrapper<Chunk>().eq(Chunk::getKnowledgeBaseId, kb.getId()).eq(Chunk::getFileId, fileId));
        if (file != null) {
            permissions.delete(new LambdaQueryWrapper<UserFilePermission>().eq(UserFilePermission::getFileId, fileId));
            if (files.deleteById(file.getId()) != 1) throw new IllegalStateException("无法删除原文件记录");
            retireArtifacts(file.getSourceObjectKey(), file.getParseArtifactObjectKey());
        }
    }

    /** Retires all file generations and pending writes while the owning knowledge base is locked. */
    public void deleteAllInKnowledgeBase(KnowledgeBase expected) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("整库文件退场必须处于知识库删除事务中");
        }
        var kb = bases.lockById(expected.getId());
        if (kb == null || !Objects.equals(kb.getCode(), expected.getCode())
                || !Objects.equals(kb.getVectorCollectionName(), expected.getVectorCollectionName())) {
            throw new IllegalStateException("原知识库身份已失效");
        }
        var relatedJobs = jobs.selectList(new LambdaQueryWrapper<DocumentImportJob>()
                .eq(DocumentImportJob::getKnowledgeBaseId, kb.getId())
                .orderByAsc(DocumentImportJob::getId).last("FOR UPDATE"));
        var oldExecutions = executions.selectList(new LambdaQueryWrapper<DocumentIndexExecution>()
                .eq(DocumentIndexExecution::getKnowledgeBaseId, kb.getId())
                .in(DocumentIndexExecution::getState, "REGISTERED", "PUBLISHED", "RECLAIMING")
                .orderByAsc(DocumentIndexExecution::getLeaseOwner).last("FOR UPDATE"));
        var ownedFiles = files.selectList(new LambdaQueryWrapper<FileInfo>()
                .eq(FileInfo::getKnowledgeBaseId, kb.getId()).orderByAsc(FileInfo::getId).last("FOR UPDATE"));
        var references = chunks.selectList(new LambdaQueryWrapper<Chunk>()
                .eq(Chunk::getKnowledgeBaseId, kb.getId()).orderByAsc(Chunk::getId).last("FOR UPDATE"));
        var cancelled = relatedJobs.stream()
                .filter(job -> !"COMPLETED".equals(job.getStatus()) && !"CANCELLED".equals(job.getStatus())).toList();
        retireExecutionsAndJobs(kb, references, oldExecutions, cancelled);
        for (var file : ownedFiles) {
            permissions.delete(new LambdaQueryWrapper<UserFilePermission>().eq(UserFilePermission::getFileId, file.getFileId()));
            retireArtifacts(file.getSourceObjectKey(), file.getParseArtifactObjectKey());
        }
        chunks.delete(new LambdaQueryWrapper<Chunk>().eq(Chunk::getKnowledgeBaseId, kb.getId()));
        if (files.delete(new LambdaQueryWrapper<FileInfo>().eq(FileInfo::getKnowledgeBaseId, kb.getId())) != ownedFiles.size()) {
            throw new IllegalStateException("整库文件退场的受影响数量不一致");
        }
    }

    private void retireExecutionsAndJobs(KnowledgeBase kb, List<Chunk> references,
            List<DocumentIndexExecution> oldExecutions, List<DocumentImportJob> cancelled) {
        var covered = referenceCoverage(oldExecutions);
        for (var chunk : references) {
            if (chunk.getVectorId() == null || chunk.getVectorId().isBlank()) {
                continue;
            }
            if (chunk.getCollectionName() == null || chunk.getCollectionName().isBlank()) {
                throw new IllegalStateException("片段缺少物理集合身份，无法安全删除");
            }
            if (!covered.test(chunk)) {
                indexStore.retireVectorReference(kb.getId(), chunk.getCollectionName(), chunk.getFileId(), chunk.getVectorId());
            }
        }
        for (var execution : oldExecutions) {
            executions.retireForFileDeletion(execution.getLeaseOwner());
        }
        for (var job : cancelled) {
            if (jobs.update(null, new LambdaUpdateWrapper<DocumentImportJob>().eq(DocumentImportJob::getId, job.getId())
                    .set(DocumentImportJob::getStatus, "CANCELLED").set(DocumentImportJob::getStage, "CANCELLED")
                    .set(DocumentImportJob::getLeaseOwner, null).set(DocumentImportJob::getLeaseUntil, null)
                    .set(DocumentImportJob::getNextAttemptAt, null).set(DocumentImportJob::getErrorCode, "FILE_DELETED")
                    .set(DocumentImportJob::getErrorMessage, "原文件已删除或被替换")) != 1) {
                throw new IllegalStateException("无法撤销原文件导入任务");
            }
            retireArtifacts(job.getSourceObjectKey(), job.getParseArtifactObjectKey());
        }
    }

    private java.util.function.Predicate<Chunk> referenceCoverage(List<DocumentIndexExecution> records) {
        var exact = new java.util.HashSet<VectorTarget>();
        var prefixes = new java.util.HashMap<VectorTarget, DocumentIndexVectorManifest>();
        for (var execution : records) {
            try {
                var manifest = new DocumentIndexVectorManifest(execution.getVectorPrefix(), execution.getVectorCount(), execution.getSingleVectorId());
                if (manifest.singleVectorId() != null) exact.add(new VectorTarget(execution.getCollectionName(), manifest.singleVectorId()));
                else prefixes.put(new VectorTarget(execution.getCollectionName(), manifest.prefix()), manifest);
            } catch (IllegalArgumentException failure) {
                // A malformed historical manifest cannot stand in for an exact surviving reference.
            }
        }
        return chunk -> {
            String id = chunk.getVectorId();
            if (exact.contains(new VectorTarget(chunk.getCollectionName(), id))) return true;
            if (id.length() <= 71 || !id.startsWith("_chunk_", 64)) return false;
            var manifest = prefixes.get(new VectorTarget(chunk.getCollectionName(), id.substring(0, 64)));
            if (manifest == null) return false;
            try {
                int index = Integer.parseInt(id.substring(71));
                return index >= 0 && index < manifest.count() && manifest.vectorId(index).equals(id);
            } catch (NumberFormatException failure) { return false; }
        };
    }

    private record VectorTarget(String collection, String identity) {}

    private void retireArtifacts(String... keys) {
        for (String key : keys) {
            if (key != null && !key.isBlank()) artifacts.retire(key);
        }
    }
}
