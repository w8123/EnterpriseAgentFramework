package com.enterprise.ai.runtime.workflow;

import com.enterprise.ai.runtime.execution.RuntimeSubWorkflowPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** SUB_WORKFLOW nodes always run an exact published version, never a draft. */
@Component
@RequiredArgsConstructor
public class RuntimeSubWorkflowResolver implements RuntimeSubWorkflowPort {

    private final RuntimePublishedWorkflowSnapshotQuery snapshots;

    @Override
    public String resolvePublishedGraph(String workflowId, Long versionId) {
        try {
            return snapshots.resolve(workflowId, versionId).graphSpecJson();
        } catch (RuntimeException ex) {
            throw new IllegalArgumentException(ex.getMessage() == null ? "发布版本不存在" : ex.getMessage(), ex);
        }
    }
}
