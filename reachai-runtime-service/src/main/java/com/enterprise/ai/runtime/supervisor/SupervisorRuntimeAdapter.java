package com.enterprise.ai.runtime.supervisor;

import com.enterprise.ai.runtime.agent.RuntimeAgentConfigVersionEntity;
import com.enterprise.ai.runtime.agent.RuntimeAgentView;
import com.enterprise.ai.runtime.agent.RuntimeAgentWorkflowToolEntity;
import com.enterprise.ai.runtime.agent.RuntimeResolvedWorkflowTarget;
import com.enterprise.ai.runtime.execution.RuntimeAgentExecutionCancellation;
import com.enterprise.ai.runtime.execution.identity.WorkflowExecutionIdentity;

import java.util.List;
import java.util.Map;

public interface SupervisorRuntimeAdapter {

    SupervisorResult execute(SupervisorRequest request);

    /**
     * Continues a Supervisor run after a Workflow interaction completes.
     * Must not re-plan from the original user intent or re-execute completed Workflow tools.
     */
    default SupervisorResult continueAfterWorkflowInteraction(Map<String, Object> continuation,
                                                              Map<String, Object> workflowResult,
                                                              SupervisorRequest request) {
        throw new UnsupportedOperationException("Supervisor continuation is not supported by this adapter");
    }

    record SupervisorRequest(RuntimeAgentView agent,
                             RuntimeAgentConfigVersionEntity config,
                             List<RuntimeAgentWorkflowToolEntity> workflowTools,
                             Map<String, Object> input,
                             PolicyApprovalGrant approvalGrant,
                             SupervisorEventSink eventSink,
                             RuntimeAgentExecutionCancellation cancellation,
                             WorkflowExecutionIdentity identity,
                             List<RuntimeResolvedWorkflowTarget> resolvedTargets) {

        public SupervisorRequest {
            eventSink = eventSink == null ? SupervisorEventSink.NOOP : eventSink;
            cancellation = cancellation == null ? RuntimeAgentExecutionCancellation.NOOP : cancellation;
            resolvedTargets = resolvedTargets == null ? List.of() : List.copyOf(resolvedTargets);
        }

        public SupervisorRequest(RuntimeAgentView agent,
                                 RuntimeAgentConfigVersionEntity config,
                                 List<RuntimeAgentWorkflowToolEntity> workflowTools,
                                 Map<String, Object> input,
                                 PolicyApprovalGrant approvalGrant,
                                 SupervisorEventSink eventSink,
                                 RuntimeAgentExecutionCancellation cancellation,
                                 WorkflowExecutionIdentity identity) {
            this(agent, config, workflowTools, input, approvalGrant, eventSink, cancellation, identity, List.of());
        }

        public SupervisorRequest(RuntimeAgentView agent,
                                 RuntimeAgentConfigVersionEntity config,
                                 List<RuntimeAgentWorkflowToolEntity> workflowTools,
                                 Map<String, Object> input,
                                 PolicyApprovalGrant approvalGrant,
                                 SupervisorEventSink eventSink,
                                 RuntimeAgentExecutionCancellation cancellation) {
            this(agent, config, workflowTools, input, approvalGrant, eventSink, cancellation, null, List.of());
        }

        public SupervisorRequest(RuntimeAgentView agent,
                                 RuntimeAgentConfigVersionEntity config,
                                 List<RuntimeAgentWorkflowToolEntity> workflowTools,
                                 Map<String, Object> input,
                                 PolicyApprovalGrant approvalGrant,
                                 SupervisorEventSink eventSink) {
            this(agent, config, workflowTools, input, approvalGrant, eventSink,
                    RuntimeAgentExecutionCancellation.NOOP, null, List.of());
        }

        public SupervisorRequest(RuntimeAgentView agent,
                                 RuntimeAgentConfigVersionEntity config,
                                 List<RuntimeAgentWorkflowToolEntity> workflowTools,
                                 Map<String, Object> input,
                                 PolicyApprovalGrant approvalGrant) {
            this(agent, config, workflowTools, input, approvalGrant, SupervisorEventSink.NOOP,
                    RuntimeAgentExecutionCancellation.NOOP, null, List.of());
        }

        public SupervisorRequest(RuntimeAgentView agent,
                                 RuntimeAgentConfigVersionEntity config,
                                 List<RuntimeAgentWorkflowToolEntity> workflowTools,
                                 Map<String, Object> input) {
            this(agent, config, workflowTools, input, null, SupervisorEventSink.NOOP,
                    RuntimeAgentExecutionCancellation.NOOP, null, List.of());
        }
    }

    @FunctionalInterface
    interface SupervisorEventSink {
        SupervisorEventSink NOOP = (event, data) -> { };

        void emit(String event, Object data);
    }

    record PolicyApprovalGrant(String interactionId,
                               String permissionKey,
                               String toolName,
                               Map<String, Object> approvedArgs,
                               String approvedBy) {
    }

    record SupervisorResult(boolean success,
                            String code,
                            String answer,
                            String traceId,
                            List<Map<String, Object>> steps,
                            Map<String, Object> metadata,
                            Object uiRequest) {
    }
}
