package com.enterprise.ai.pipeline.document.job;

import com.enterprise.ai.domain.entity.DocumentIndexExecution;
import com.enterprise.ai.repository.DocumentIndexExecutionRepository;
import com.enterprise.ai.vector.VectorService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Reclaims unpublished writes and explicitly retired vectors with durable progress. */
@Slf4j
@Component
@RequiredArgsConstructor
public class DocumentIndexReclaimer {
    private final DocumentIndexExecutionRepository executions;
    private final DocumentIndexExecutionStore store;
    private final VectorService vectors;
    private final DocumentImportJobProperties properties;

    @Scheduled(fixedDelayString = "${reachai.knowledge.document-import-worker.cleanup-interval-ms:10000}")
    public void reclaimPending() {
        if (!properties.isEnabled()) return;
        for (var candidate : executions.findReclaimable(8)) {
            try {
                var execution = store.claim(candidate, 60);
                if (execution != null) reclaim(execution);
            } catch (Exception e) {
                log.warn("索引回收暂未完成: lease={}, errorType={}", candidate.getLeaseOwner(), e.getClass().getSimpleName());
            }
        }
    }

    private void reclaim(DocumentIndexExecution execution) {
        int cursor = execution.getCleanupCursor();
        String error = null;
        try {
            var manifest = new DocumentIndexVectorManifest(execution.getVectorPrefix(), execution.getVectorCount(), execution.getSingleVectorId());
            var batch = manifest.batch(cursor, 100);
            if (store.hasPublishedReferences(execution, batch)) {
                executions.progress(execution.getLeaseOwner(), execution.getCleanupLeaseOwner(), cursor,
                        cursor, "RECLAIMING", 60, null);
                return;
            }
            for (String id : batch) {
                vectors.deleteById(execution.getCollectionName(), id);
                if (executions.checkpoint(execution.getLeaseOwner(), execution.getCleanupLeaseOwner(), cursor, cursor + 1) != 1) return;
                cursor++;
            }
        } catch (Exception e) {
            error = e.getClass().getSimpleName();
            if (error.length() > 128) error = error.substring(0, 128);
        }
        boolean swept = cursor == execution.getVectorCount();
        boolean done = swept && Boolean.TRUE.equals(execution.getWriteAcknowledged());
        int delay = error != null ? 30 : swept && !done ? 300 : 0;
        executions.progress(execution.getLeaseOwner(), execution.getCleanupLeaseOwner(), cursor,
                swept && !done ? 0 : cursor, done ? "RECLAIMED" : "RECLAIMING", delay, error);
    }
}
