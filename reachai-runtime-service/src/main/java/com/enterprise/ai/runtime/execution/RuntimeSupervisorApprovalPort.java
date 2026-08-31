package com.enterprise.ai.runtime.execution;

import com.enterprise.ai.runtime.execution.SupervisorRuntimeAdapter.PolicyApprovalGrant;
import com.enterprise.ai.runtime.execution.identity.WorkflowExecutionIdentity;

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

    interface ResumeDecision {
        boolean approved();
        boolean rejected();
        String agentId();
        Map<String, Object> originalInput();
        PolicyApprovalGrant grant();
        String message();
        String idempotencyKey();
        Map<String, Object> submittedPayload();
        Map<String, Object> replayResult();
    }
}
