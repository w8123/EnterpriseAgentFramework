package com.enterprise.ai.runtime.workflow;

import java.util.List;

public interface RuntimeWorkflowExecutionQuery {
    List<Target> resolve(List<Reference> references);

    default Target resolveOne(String workflowId, Long versionId) {
        return resolve(List.of(new Reference(workflowId, versionId))).get(0);
    }

    record Reference(String workflowId, Long versionId) { }
    record Target(RuntimeWorkflowExecutionView workflow, RuntimeWorkflowPublishedVersionView version) { }

    enum Reason { WORKFLOW_UNAVAILABLE, VERSION_INVALID }

    final class LookupFailure extends IllegalStateException {
        private final Reason reason;

        public LookupFailure(Reason reason, String message) {
            super(message);
            this.reason = reason;
        }

        public Reason reason() {
            return reason;
        }
    }
}
