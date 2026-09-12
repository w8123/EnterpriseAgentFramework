package com.enterprise.ai.pipeline.document.job;

import com.enterprise.ai.pipeline.PipelineContext;
import com.enterprise.ai.pipeline.PipelineException;
import com.enterprise.ai.repository.DocumentImportJobRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Holds the job row until the metadata transaction commits or rolls back. */
@Component
@RequiredArgsConstructor
public class DocumentImportPublicationGuard {
    private final DocumentImportJobRepository jobs;
    private final DocumentIndexExecutionStore executions;
    private final com.enterprise.ai.service.impl.KnowledgeFileDeletionService fileDeletion;

    public void lockOwnedExecution(PipelineContext context, Long knowledgeBaseId) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("索引发布必须处于元数据事务中");
        }
        executions.lockForPublication(context, knowledgeBaseId);
    }

    private PipelineException denied(PipelineContext context) {
        return new PipelineException("METADATA_PERSIST", context.getFileId(), "索引执行租约已失效，拒绝发布元数据");
    }

    public void completeInMetadataTransaction(PipelineContext context) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager.isSynchronizationActive()) {
            throw new IllegalStateException("索引完成必须处于元数据事务中");
        }
        if (context.getImportJobId() != null) {
            var job = jobs.lockValidIndexing(context.getImportJobId(), context.getImportLeaseOwner());
            if (job == null) throw denied(context);
            fileDeletion.retireReplacement(job);
            if (jobs.finalizeIndexing(context.getImportJobId(), context.getImportLeaseOwner(),
                    java.time.LocalDateTime.now()) != 1) throw denied(context);
        }
        executions.publish(context);
        TransactionSynchronizationManager.registerSynchronization(
                new org.springframework.transaction.support.TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        context.setImportPublished(true);
                    }
                });
    }
}
