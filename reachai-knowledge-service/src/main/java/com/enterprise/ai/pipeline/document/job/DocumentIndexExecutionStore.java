package com.enterprise.ai.pipeline.document.job;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.enterprise.ai.domain.entity.Chunk;
import com.enterprise.ai.domain.entity.KnowledgeBase;
import com.enterprise.ai.domain.entity.DocumentImportJob;
import com.enterprise.ai.domain.entity.DocumentIndexExecution;
import com.enterprise.ai.pipeline.PipelineContext;
import com.enterprise.ai.pipeline.PipelineException;
import com.enterprise.ai.repository.DocumentImportJobRepository;
import com.enterprise.ai.repository.DocumentIndexExecutionRepository;
import com.enterprise.ai.repository.KnowledgeBaseRepository;
import com.enterprise.ai.repository.FileInfoRepository;
import com.enterprise.ai.repository.ChunkRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.Objects;
import java.util.UUID;

/** Short database transactions; never hold a database lock across a Milvus call. */
@Component
public class DocumentIndexExecutionStore {
    private final DocumentImportJobRepository jobs;
    private final DocumentIndexExecutionRepository executions;
    private final KnowledgeBaseRepository bases;
    private final FileInfoRepository files;
    private final ChunkRepository chunks;
    private final TransactionTemplate independent;

    public DocumentIndexExecutionStore(DocumentImportJobRepository jobs, DocumentIndexExecutionRepository executions,
                                       KnowledgeBaseRepository bases, FileInfoRepository files, ChunkRepository chunks,
                                       PlatformTransactionManager transactionManager) {
        this.jobs = jobs;
        this.executions = executions;
        this.bases = bases; this.files = files; this.chunks = chunks;
        independent = new TransactionTemplate(transactionManager);
        independent.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** Only the committed INSERT winner receives permission for one external write. */
    public boolean register(PipelineContext context, DocumentIndexVectorManifest manifest) {
        String identity = executionId(context);
        String operation = operation(context);
        if (context.getKnowledgeBaseId() == null || context.getVectorCollectionName() == null
                || context.getVectorCollectionName().isBlank()) throw denied(context);
        if (!manifest.equals(DocumentIndexVectorManifest.forExecution(context.getFileId(), identity, manifest.count()))) {
            throw denied(context);
        }
        return Boolean.TRUE.equals(independent.execute(status -> {
            lockKnowledgeBase(context);
            var ownedJob = "JOB".equals(operation) ? lockOwnedJob(context) : null;
            var old = executions.lock(identity);
            if (old != null) {
                requireIdentity(old, context, context.getKnowledgeBaseId(), manifest);
                return false;
            }
            if (!"JOB".equals(operation)) lockStandaloneTarget(context);
            else lockReplacementFile(ownedJob, context);
            var execution = new DocumentIndexExecution();
            execution.setLeaseOwner(identity);
            execution.setJobId(context.getImportJobId());
            execution.setOperationType(operation);
            execution.setFileId(context.getFileId());
            execution.setKnowledgeBaseId(context.getKnowledgeBaseId());
            execution.setCollectionName(context.getVectorCollectionName());
            execution.setVectorPrefix(manifest.prefix()); execution.setVectorCount(manifest.count());
            var target = context.getIndexTarget();
            if (target != null) {
                execution.setTargetFileRowId(target.fileRowId()); execution.setTargetChunkId(target.chunkId());
                execution.setTargetFileGeneration(target.fileGeneration());
                execution.setTargetVectorId(target.vectorId()); execution.setTargetCollectionName(target.collectionName());
                execution.setTargetContentHash(target.contentHash());
            }
            if (executions.insert(execution) != 1) throw denied(context);
            if (!"JOB".equals(operation) && executions.startPublicationDeadline(identity, 1200) != 1) throw denied(context);
            return true;
        }));
    }

    public void acknowledge(String lease) {
        independent.executeWithoutResult(status -> {
            if (executions.acknowledge(lease) != 1) throw new IllegalStateException("无法确认索引写入完成");
        });
    }

    public void lockForPublication(PipelineContext context, Long knowledgeBaseId) {
        requireTransaction();
        lockKnowledgeBase(context);
        if (!Objects.equals(context.getKnowledgeBaseId(), knowledgeBaseId)) throw denied(context);
        boolean job = "JOB".equals(operation(context));
        var ownedJob = job ? lockOwnedJob(context) : null;
        String identity = executionId(context);
        var execution = executions.lock(identity);
        if (execution == null || !"REGISTERED".equals(execution.getState())
                || !Boolean.TRUE.equals(execution.getWriteAcknowledged())) throw denied(context);
        if (!job && executions.lockPublicationOpen(identity) == null) throw denied(context);
        var ids = context.getVectorIds();
        if (ids == null || ids.isEmpty() || context.getChunks() == null
                || ids.size() != context.getChunks().size()) throw denied(context);
        var manifest = DocumentIndexVectorManifest.forExecution(context.getFileId(), identity, ids.size());
        requireIdentity(execution, context, knowledgeBaseId, manifest);
        for (int i = 0; i < ids.size(); i++) if (!manifest.vectorId(i).equals(ids.get(i))) throw denied(context);
        if (!job) lockStandaloneTarget(context);
        else lockReplacementFile(ownedJob, context);
    }

    public void publish(PipelineContext context) {
        requireTransaction();
        if (executions.publish(executionId(context)) != 1) throw denied(context);
    }

    /** New vector reference, retirement evidence and publication commit in the same short transaction. */
    public void publishReplacement(PipelineContext context) {
        if (!"REEMBED".equals(operation(context))) throw denied(context);
        lockForPublication(context, context.getKnowledgeBaseId());
        var target = context.getIndexTarget();
        if (chunks.update(null, new LambdaUpdateWrapper<Chunk>().eq(Chunk::getId, target.chunkId())
                .set(Chunk::getVectorId, context.getVectorIds().get(0))
                .set(Chunk::getCollectionName, context.getVectorCollectionName())) != 1) throw denied(context);
        retirePreviousVector(context, target);
        publish(context);
    }

    public void abandonStandalone(PipelineContext context) {
        if (context.getImportJobId() == null && context.getIndexExecutionId() != null) {
            independent.executeWithoutResult(status -> executions.abandonStandalone(context.getIndexExecutionId()));
        }
    }

    public DocumentIndexExecution claim(DocumentIndexExecution candidate, int leaseSeconds) {
        return independent.execute(status -> {
            // Missing knowledge bases never prevent recovery of an independently retained physical target.
            bases.lockById(candidate.getKnowledgeBaseId());
            if (candidate.getJobId() != null) jobs.selectOne(new LambdaQueryWrapper<DocumentImportJob>()
                    .eq(DocumentImportJob::getJobId, candidate.getJobId()).last("FOR UPDATE"));
            var execution = executions.lock(candidate.getLeaseOwner());
            if (execution == null || !Objects.equals(candidate.getJobId(), execution.getJobId())) return null;
            if ("RETIRED_VECTOR".equals(execution.getOperationType()) && chunks.selectCount(new LambdaQueryWrapper<Chunk>()
                    .eq(Chunk::getCollectionName, execution.getCollectionName()).eq(Chunk::getVectorId, execution.getSingleVectorId())) > 0) {
                executions.deferReferencedRetirement(execution.getLeaseOwner());
                return null;
            }
            if ("REGISTERED".equals(execution.getState())) {
                if ("JOB".equals(execution.getOperationType())) {
                    if (jobs.selectCount(new LambdaQueryWrapper<DocumentImportJob>()
                            .eq(DocumentImportJob::getJobId, execution.getJobId()).eq(DocumentImportJob::getStatus, "INDEXING")
                            .eq(DocumentImportJob::getLeaseOwner, execution.getLeaseOwner())
                            .apply("lease_until > CURRENT_TIMESTAMP")) > 0) return null;
                } else if (executions.lockPublicationOpen(execution.getLeaseOwner()) != null) return null;
            }
            String owner = UUID.randomUUID().toString();
            if (executions.claim(execution.getLeaseOwner(), owner, leaseSeconds) != 1) return null;
            return executions.lock(execution.getLeaseOwner());
        });
    }

    private DocumentImportJob lockOwnedJob(PipelineContext context) {
        if (context.getImportJobId() == null || context.getImportJobId().isBlank()
                || context.getImportLeaseOwner() == null || context.getImportLeaseOwner().isBlank()) throw denied(context);
        var job = jobs.selectOne(new LambdaQueryWrapper<DocumentImportJob>()
                .eq(DocumentImportJob::getJobId, context.getImportJobId()).last("FOR UPDATE"));
        if (job == null || !Objects.equals(job.getJobId(), context.getImportJobId())
                || !Objects.equals(job.getFileId(), context.getFileId())
                || !Objects.equals(job.getKnowledgeBaseCode(), context.getKnowledgeBaseCode())
                || !Objects.equals(job.getKnowledgeBaseId(), context.getKnowledgeBaseId())
                || job.getVectorCollectionName() == null || job.getVectorCollectionName().isBlank()
                || !Objects.equals(job.getVectorCollectionName(), context.getVectorCollectionName())
                || !Objects.equals(job.getLeaseOwner(), context.getImportLeaseOwner())) throw denied(context);
        if (jobs.lockValidIndexing(context.getImportJobId(), context.getImportLeaseOwner()) == null) throw denied(context);
        return job;
    }

    private void requireIdentity(DocumentIndexExecution e, PipelineContext c, Long kb, DocumentIndexVectorManifest manifest) {
        if (!Objects.equals(e.getJobId(), c.getImportJobId()) || !Objects.equals(e.getFileId(), c.getFileId())
                || !Objects.equals(e.getOperationType(), operation(c)) || e.getSingleVectorId() != null
                || !Objects.equals(e.getKnowledgeBaseId(), kb) || !Objects.equals(e.getCollectionName(), c.getVectorCollectionName())
                || !Objects.equals(e.getVectorPrefix(), manifest.prefix()) || !Objects.equals(e.getVectorCount(), manifest.count())) throw denied(c);
        var target = c.getIndexTarget();
        if (target == null) {
            if (e.getTargetFileRowId() != null || e.getTargetFileGeneration() != null || e.getTargetChunkId() != null || e.getTargetVectorId() != null
                    || e.getTargetCollectionName() != null || e.getTargetContentHash() != null) throw denied(c);
        } else if (!Objects.equals(e.getTargetFileRowId(), target.fileRowId()) || !Objects.equals(e.getTargetChunkId(), target.chunkId())
                || !Objects.equals(e.getTargetFileGeneration(), target.fileGeneration())
                || !Objects.equals(e.getTargetVectorId(), target.vectorId()) || !Objects.equals(e.getTargetCollectionName(), target.collectionName())
                || !Objects.equals(e.getTargetContentHash(), target.contentHash())) throw denied(c);
    }

    private void lockReplacementFile(DocumentImportJob job, PipelineContext context) {
        if (job.getReplaceFileId() == null) return;
        if (job.getReplaceFileId().isBlank() || job.getReplaceFileRowId() == null
                || job.getReplaceFileId().equals(job.getFileId())) throw denied(context);
        var original = files.lockByFileId(job.getReplaceFileId());
        if (original == null || !original.hasRecordIdentity(job.getReplaceFileRowId(), job.getReplaceFileGeneration())
                || !Objects.equals(original.getKnowledgeBaseId(), job.getKnowledgeBaseId())) throw denied(context);
    }

    public static String executionId(PipelineContext context) {
        boolean job = context.getImportJobId() != null;
        String identity = job ? context.getImportLeaseOwner() : context.getIndexExecutionId();
        if (identity == null || identity.isBlank() || identity.length() > 128
                || (job && context.getIndexExecutionId() != null)
                || (!job && context.getImportLeaseOwner() != null)) {
            throw new PipelineException("INDEX_EXECUTION", context.getFileId(), "缺少唯一索引执行身份");
        }
        return identity;
    }

    private String operation(PipelineContext context) {
        if (context.getImportJobId() != null) {
            if (context.getIndexOperation() != null || context.getIndexTarget() != null) throw denied(context);
            return "JOB";
        }
        String operation = context.getIndexOperation();
        if (!java.util.Set.of("DIRECT", "PIPELINE", "REEMBED").contains(operation == null ? "" : operation)) throw denied(context);
        if ("REEMBED".equals(operation) != (context.getIndexTarget() != null)) throw denied(context);
        return operation;
    }

    private KnowledgeBase lockKnowledgeBase(PipelineContext context) {
        var kb = bases.lockById(context.getKnowledgeBaseId());
        if (kb == null || !Objects.equals(kb.getCode(), context.getKnowledgeBaseCode())
                || !Objects.equals(kb.getVectorCollectionName(), context.getVectorCollectionName())) throw denied(context);
        return kb;
    }

    private void lockStandaloneTarget(PipelineContext context) {
        var file = files.lockByFileId(context.getFileId());
        if (!"REEMBED".equals(operation(context))) {
            if (file != null) throw denied(context);
            return;
        }
        var target = context.getIndexTarget();
        if (file == null || !file.hasRecordIdentity(target.fileRowId(), target.fileGeneration())
                || !Objects.equals(file.getKnowledgeBaseId(), context.getKnowledgeBaseId())) throw denied(context);
        var chunk = chunks.lockById(target.chunkId());
        if (chunk == null || !Objects.equals(chunk.getFileId(), context.getFileId())
                || !Objects.equals(chunk.getKnowledgeBaseId(), context.getKnowledgeBaseId())
                || !Objects.equals(chunk.getVectorId(), target.vectorId())
                || !Objects.equals(chunk.getCollectionName(), target.collectionName())
                || !Objects.equals(DocumentIndexTargetSnapshot.hash(chunk.getContent()), target.contentHash())
                || context.getChunks() == null || context.getChunks().size() != 1
                || !Objects.equals(context.getChunks().get(0), chunk.getContent())) throw denied(context);
        if (target.vectorId() != null && !target.vectorId().isBlank()
                && !Objects.equals(target.collectionName(), context.getVectorCollectionName())) throw denied(context);
    }

    private void retirePreviousVector(PipelineContext context, DocumentIndexTargetSnapshot target) {
        String old = target.vectorId();
        if (old == null || old.isBlank()) return;
        if (old.equals(context.getVectorIds().get(0))) throw denied(context);
        retireVectorReference(context.getKnowledgeBaseId(), target.collectionName(), context.getFileId(), old);
    }

    public void retireVectorReference(Long knowledgeBaseId, String collectionName, String fileId, String vectorId) {
        requireTransaction();
        if (collectionName == null || collectionName.isBlank()) throw new IllegalStateException("片段缺少物理集合身份，无法安全回收");
        var retirement = new DocumentIndexExecution();
        retirement.setLeaseOwner("retired_" + UUID.randomUUID().toString().replace("-", ""));
        retirement.setOperationType("RETIRED_VECTOR"); retirement.setFileId(fileId);
        retirement.setKnowledgeBaseId(knowledgeBaseId); retirement.setCollectionName(collectionName);
        retirement.setSingleVectorId(new DocumentIndexVectorManifest(null, 1, vectorId).singleVectorId());
        retirement.setVectorCount(1); retirement.setState("RECLAIMING");
        retirement.setWriteAcknowledged(hasConfirmedSource(knowledgeBaseId, collectionName, fileId, vectorId));
        if (executions.insert(retirement) != 1) throw new IllegalStateException("无法登记向量回收身份");
    }

    private boolean hasConfirmedSource(Long knowledgeBaseId, String collectionName, String fileId, String vectorId) {
        if (!vectorId.matches("[0-9a-f]{64}_chunk_[0-9]+")) return false;
        var source = executions.selectOne(new LambdaQueryWrapper<DocumentIndexExecution>()
                .eq(DocumentIndexExecution::getVectorPrefix, vectorId.substring(0, 64)));
        if (source == null || !Boolean.TRUE.equals(source.getWriteAcknowledged())
                || !"PUBLISHED".equals(source.getState()) || source.getSingleVectorId() != null
                || !Objects.equals(source.getFileId(), fileId)
                || !Objects.equals(source.getKnowledgeBaseId(), knowledgeBaseId)
                || !Objects.equals(source.getCollectionName(), collectionName)) return false;
        try {
            int index = Integer.parseInt(vectorId.substring(71));
            return index < source.getVectorCount()
                    && new DocumentIndexVectorManifest(source.getVectorPrefix(), source.getVectorCount()).vectorId(index).equals(vectorId);
        }
        catch (NumberFormatException e) { return false; }
    }

    public boolean hasPublishedReferences(DocumentIndexExecution execution, java.util.List<String> vectorIds) {
        if (vectorIds.isEmpty()) return false;
        return chunks.selectCount(new LambdaQueryWrapper<Chunk>().eq(Chunk::getCollectionName, execution.getCollectionName())
                .in(Chunk::getVectorId, vectorIds)) > 0;
    }

    private void requireTransaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("索引发布必须处于元数据事务中");
    }

    private PipelineException denied(PipelineContext context) {
        return new PipelineException("INDEX_EXECUTION", context.getFileId(), "索引执行租约或清单不匹配，拒绝写入和发布");
    }
}
