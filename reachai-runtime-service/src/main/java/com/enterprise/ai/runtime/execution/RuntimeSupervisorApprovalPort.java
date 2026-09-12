package com.enterprise.ai.runtime.execution;

import com.enterprise.ai.runtime.identity.WorkflowExecutionIdentity;
import java.util.Map;

/** Execution-owned port for resuming a durable Supervisor approval interaction. */
public interface RuntimeSupervisorApprovalPort {

    String INTERACTION_PREFIX = "spv_";

    ResumeDecision prepareResume(String interactionId,
                                 Map<String, Object> submission,
                                 WorkflowExecutionIdentity trustedIdentity);

    Map<String, Object> completeResume(String interactionId,
                                       String idempotencyKey,
                                       Map<String, Object> submittedPayload,
                                       Map<String, Object> result);

    record PolicyApprovalGrant(String interactionId,
                               String permissionKey,
                               String toolName,
                               Map<String, Object> approvedArgs,
                               String approvedBy) {
    }

    interface ResumeDecision {
        boolean approved();
        boolean rejected();
        String agentId();
        Long agentConfigVersionId();
        Map<String, Object> originalInput();
        PolicyApprovalGrant grant();
        String message();
        String idempotencyKey();
        Map<String, Object> submittedPayload();
        Map<String, Object> replayResult();
    }
}
