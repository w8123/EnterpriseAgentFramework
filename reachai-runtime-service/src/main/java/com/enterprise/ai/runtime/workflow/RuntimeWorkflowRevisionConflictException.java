package com.enterprise.ai.runtime.workflow;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.CONFLICT)
public class RuntimeWorkflowRevisionConflictException extends RuntimeException {

    private final String workflowId;
    private final String baseRevision;
    private final String currentRevision;

    public RuntimeWorkflowRevisionConflictException(String workflowId,
                                                    String baseRevision,
                                                    String currentRevision) {
        super("workflow draft revision conflict: workflowId=" + workflowId
                + ", baseRevision=" + baseRevision
                + ", currentRevision=" + currentRevision);
        this.workflowId = workflowId;
        this.baseRevision = baseRevision;
        this.currentRevision = currentRevision;
    }

    public String getWorkflowId() {
        return workflowId;
    }

    public String getBaseRevision() {
        return baseRevision;
    }

    public String getCurrentRevision() {
        return currentRevision;
    }
}
