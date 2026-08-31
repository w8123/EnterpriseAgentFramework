package com.enterprise.ai.control.a2a.application.outbound;

import java.util.List;

/** Exact Runtime-to-Control contract for one governed outbound A2A delegation. */
public final class A2aOutboundDelegationContracts {

    private A2aOutboundDelegationContracts() {
    }

    public record SendRequest(
            Long bindingId,
            String runtimeAgentId,
            Long agentConfigVersionId,
            Long principalId,
            Long remoteAgentId,
            Long remoteAgentRevisionId,
            String runtimeSessionId,
            String contextId,
            String taskId,
            String messageId,
            String text,
            String protocolSkillId,
            String contentClassification,
            List<String> acceptedOutputModes,
            Integer historyLength,
            Long timeoutMs,
            String traceId) {

        public SendRequest {
            acceptedOutputModes = acceptedOutputModes == null
                    ? List.of() : List.copyOf(acceptedOutputModes);
        }
    }

    public record SendResponse(
            String schema,
            String taskId,
            String contextId,
            String remoteTaskId,
            String remoteContextId,
            String state,
            String safeSummary,
            String errorCode,
            boolean idempotentReplay,
            List<String> agentMessages,
            List<String> artifacts) {

        public SendResponse {
            agentMessages = agentMessages == null ? List.of() : List.copyOf(agentMessages);
            artifacts = artifacts == null ? List.of() : List.copyOf(artifacts);
        }
    }
}
