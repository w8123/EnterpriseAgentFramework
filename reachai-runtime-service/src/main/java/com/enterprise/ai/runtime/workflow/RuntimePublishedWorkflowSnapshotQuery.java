package com.enterprise.ai.runtime.workflow;

/** Exact published version lookup, independent of the current Workflow editing/activation state. */
public interface RuntimePublishedWorkflowSnapshotQuery {
    RuntimePublishedWorkflowSnapshot resolve(String workflowId, Long versionId);

    enum Reason { VERSION_NOT_FOUND, SNAPSHOT_INVALID }

    final class LookupFailure extends IllegalArgumentException {
        private final Reason reason;

        public LookupFailure(Reason reason, String message) {
            super(message);
            this.reason = reason;
        }

        public Reason reason() { return reason; }
    }
}
