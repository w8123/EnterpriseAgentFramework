package com.enterprise.ai.runtime.supervisor;

import com.enterprise.ai.runtime.agent.RuntimeAgentConfigVersionEntity;
import com.enterprise.ai.runtime.agent.RuntimeAgentView;
import com.enterprise.ai.runtime.agent.RuntimeAgentWorkflowToolEntity;
import com.enterprise.ai.runtime.execution.RuntimeAgentExecutionCancellation;

import java.util.List;
import java.util.Map;

public interface SupervisorRuntimeAdapter {

    SupervisorResult execute(SupervisorRequest request);

    record SupervisorRequest(RuntimeAgentView agent,
                             RuntimeAgentConfigVersionEntity config,
                             List<RuntimeAgentWorkflowToolEntity> workflowTools,
                             Map<String, Object> input,
                             PolicyApprovalGrant approvalGrant,
                             SupervisorEventSink eventSink,
                             RuntimeAgentExecutionCancellation cancellation) {

        public SupervisorRequest {
            eventSink = eventSink == null ? SupervisorEventSink.NOOP : eventSink;
            cancellation = cancellation == null ? RuntimeAgentExecutionCancellation.NOOP : cancellation;
        }

        public SupervisorRequest(RuntimeAgentView agent,
                                 RuntimeAgentConfigVersionEntity config,
                                 List<RuntimeAgentWorkflowToolEntity> workflowTools,
                                 Map<String, Object> input,
                                 PolicyApprovalGrant approvalGrant,
                                 SupervisorEventSink eventSink) {
            this(agent, config, workflowTools, input, approvalGrant, eventSink,
                    RuntimeAgentExecutionCancellation.NOOP);
        }

        public SupervisorRequest(RuntimeAgentView agent,
                                 RuntimeAgentConfigVersionEntity config,
                                 List<RuntimeAgentWorkflowToolEntity> workflowTools,
                                 Map<String, Object> input,
                                 PolicyApprovalGrant approvalGrant) {
            this(agent, config, workflowTools, input, approvalGrant, SupervisorEventSink.NOOP,
                    RuntimeAgentExecutionCancellation.NOOP);
        }

        public SupervisorRequest(RuntimeAgentView agent,
                                 RuntimeAgentConfigVersionEntity config,
                                 List<RuntimeAgentWorkflowToolEntity> workflowTools,
                                 Map<String, Object> input) {
            this(agent, config, workflowTools, input, null, SupervisorEventSink.NOOP,
                    RuntimeAgentExecutionCancellation.NOOP);
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
