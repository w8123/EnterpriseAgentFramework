package com.enterprise.ai.runtime.workflow;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/** Workflow owns version persistence and release validation; callers receive only immutable execution data. */
@Service
@RequiredArgsConstructor
public class RuntimePublishedWorkflowSnapshotReader implements RuntimePublishedWorkflowSnapshotQuery {
    private final RuntimeWorkflowVersionMapper versions;

    @Override
    @Transactional(readOnly = true)
    public RuntimePublishedWorkflowSnapshot resolve(String workflowId, Long versionId) {
        if (!StringUtils.hasText(workflowId) || versionId == null || versionId <= 0) {
            throw missing();
        }
        RuntimeWorkflowVersionEntity version = versions.selectById(versionId);
        // Preserve exact Workflow identity comparison, regardless of the database collation.
        if (version == null || !workflowId.equals(version.getWorkflowId())) throw missing();
        try {
            return RuntimePublishedWorkflowSnapshot.read(version);
        } catch (IllegalArgumentException invalid) {
            throw new LookupFailure(Reason.SNAPSHOT_INVALID, "Published Workflow snapshot is not executable");
        }
    }

    private LookupFailure missing() {
        return new LookupFailure(Reason.VERSION_NOT_FOUND, "Workflow version not found for the requested Workflow");
    }
}
