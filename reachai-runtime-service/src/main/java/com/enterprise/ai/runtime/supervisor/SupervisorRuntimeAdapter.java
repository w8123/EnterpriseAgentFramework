package com.enterprise.ai.runtime.supervisor;

import com.enterprise.ai.runtime.execution.RuntimeSupervisorApprovalPort.PolicyApprovalGrant;
import com.enterprise.ai.runtime.execution.RuntimeAgentExecutionEventSink;
import com.enterprise.ai.runtime.execution.TrustedPersonalMemoryContext;
import com.enterprise.ai.runtime.execution.RuntimeAgentExecutionCancellation;
import com.enterprise.ai.runtime.agent.RuntimeAgentConfigSnapshot;
import com.enterprise.ai.runtime.agent.RuntimeAgentSkillBindingSnapshot;
import com.enterprise.ai.runtime.agent.RuntimeAgentView;
import com.enterprise.ai.runtime.agent.RuntimeAgentWorkflowToolSnapshot;
import com.enterprise.ai.runtime.agent.RuntimeResolvedWorkflowTarget;
import com.enterprise.ai.runtime.identity.WorkflowExecutionIdentity;
import com.enterprise.ai.runtime.execution.policy.RuntimeEvalExecutionContext;
import java.util.List;
import java.util.Map;

/** Supervisor orchestration contract implemented by the configured planning runtime. */
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
                             RuntimeAgentConfigSnapshot config,
                             List<RuntimeAgentWorkflowToolSnapshot> workflowTools,
                             Map<String, Object> input,
                             PolicyApprovalGrant approvalGrant,
                             RuntimeAgentExecutionEventSink eventSink,
                             RuntimeAgentExecutionCancellation cancellation,
                             WorkflowExecutionIdentity identity,
                             List<RuntimeResolvedWorkflowTarget> resolvedTargets,
                             TrustedPersonalMemoryContext personalMemory,
                             List<RuntimeAgentSkillBindingSnapshot> skills,
                             List<RemoteAgentBinding> remoteAgents,
                             RuntimeEvalExecutionContext evalContext) {

        public SupervisorRequest {
            workflowTools = workflowTools == null ? List.of() : List.copyOf(workflowTools);
            eventSink = eventSink == null ? RuntimeAgentExecutionEventSink.NOOP : eventSink;
            cancellation = cancellation == null ? RuntimeAgentExecutionCancellation.NOOP : cancellation;
            resolvedTargets = resolvedTargets == null ? List.of() : List.copyOf(resolvedTargets);
            personalMemory = personalMemory == null ? TrustedPersonalMemoryContext.empty() : personalMemory;
            skills = skills == null ? List.of() : List.copyOf(skills);
            remoteAgents = remoteAgents == null ? List.of() : List.copyOf(remoteAgents);
            evalContext = evalContext == null ? RuntimeEvalExecutionContext.none() : evalContext;
        }

        /** Replace turn input while retaining the published bindings and trusted execution context. */
        public SupervisorRequest withInput(Map<String, Object> nextInput) {
            return new SupervisorRequest(agent, config, workflowTools, nextInput, approvalGrant, eventSink,
                    cancellation, identity, resolvedTargets, personalMemory, skills, remoteAgents, evalContext);
        }

        public SupervisorRequest(RuntimeAgentView agent,
                                 RuntimeAgentConfigSnapshot config,
                                 List<RuntimeAgentWorkflowToolSnapshot> workflowTools,
                                 Map<String, Object> input,
                                 PolicyApprovalGrant approvalGrant,
                                 RuntimeAgentExecutionEventSink eventSink,
                                 RuntimeAgentExecutionCancellation cancellation,
                                 WorkflowExecutionIdentity identity,
                                 List<RuntimeResolvedWorkflowTarget> resolvedTargets,
                                 TrustedPersonalMemoryContext personalMemory,
                                 List<RuntimeAgentSkillBindingSnapshot> skills,
                                 List<RemoteAgentBinding> remoteAgents) {
            this(agent, config, workflowTools, input, approvalGrant, eventSink, cancellation,
                    identity, resolvedTargets, personalMemory, skills, remoteAgents,
                    RuntimeEvalExecutionContext.none());
        }

        public SupervisorRequest(RuntimeAgentView agent,
                                 RuntimeAgentConfigSnapshot config,
                                 List<RuntimeAgentWorkflowToolSnapshot> workflowTools,
                                 Map<String, Object> input,
                                 PolicyApprovalGrant approvalGrant,
                                 RuntimeAgentExecutionEventSink eventSink,
                                 RuntimeAgentExecutionCancellation cancellation,
                                 WorkflowExecutionIdentity identity,
                                 List<RuntimeResolvedWorkflowTarget> resolvedTargets,
                                 TrustedPersonalMemoryContext personalMemory,
                                 List<RuntimeAgentSkillBindingSnapshot> skills) {
            this(agent, config, workflowTools, input, approvalGrant, eventSink, cancellation,
                    identity, resolvedTargets, personalMemory, skills, List.of(),
                    RuntimeEvalExecutionContext.none());
        }

        public SupervisorRequest(RuntimeAgentView agent,
                                 RuntimeAgentConfigSnapshot config,
                                 List<RuntimeAgentWorkflowToolSnapshot> workflowTools,
                                 Map<String, Object> input,
                                 PolicyApprovalGrant approvalGrant,
                                 RuntimeAgentExecutionEventSink eventSink,
                                 RuntimeAgentExecutionCancellation cancellation,
                                 WorkflowExecutionIdentity identity,
                                 List<RuntimeResolvedWorkflowTarget> resolvedTargets,
                                 TrustedPersonalMemoryContext personalMemory) {
            this(agent, config, workflowTools, input, approvalGrant, eventSink, cancellation,
                    identity, resolvedTargets, personalMemory, List.of(), List.of(),
                    RuntimeEvalExecutionContext.none());
        }

        public SupervisorRequest(RuntimeAgentView agent,
                                 RuntimeAgentConfigSnapshot config,
                                 List<RuntimeAgentWorkflowToolSnapshot> workflowTools,
                                 Map<String, Object> input,
                                 PolicyApprovalGrant approvalGrant,
                                 RuntimeAgentExecutionEventSink eventSink,
                                 RuntimeAgentExecutionCancellation cancellation,
                                 WorkflowExecutionIdentity identity,
                                 List<RuntimeResolvedWorkflowTarget> resolvedTargets) {
            this(agent, config, workflowTools, input, approvalGrant, eventSink, cancellation, identity,
                    resolvedTargets, TrustedPersonalMemoryContext.empty(), List.of(), List.of(),
                    RuntimeEvalExecutionContext.none());
        }

        public SupervisorRequest(RuntimeAgentView agent,
                                 RuntimeAgentConfigSnapshot config,
                                 List<RuntimeAgentWorkflowToolSnapshot> workflowTools,
                                 Map<String, Object> input,
                                 PolicyApprovalGrant approvalGrant,
                                 RuntimeAgentExecutionEventSink eventSink,
                                 RuntimeAgentExecutionCancellation cancellation,
                                 WorkflowExecutionIdentity identity) {
            this(agent, config, workflowTools, input, approvalGrant, eventSink, cancellation, identity, List.of(),
                    TrustedPersonalMemoryContext.empty(), List.of(), List.of(), RuntimeEvalExecutionContext.none());
        }

        public SupervisorRequest(RuntimeAgentView agent,
                                 RuntimeAgentConfigSnapshot config,
                                 List<RuntimeAgentWorkflowToolSnapshot> workflowTools,
                                 Map<String, Object> input,
                                 PolicyApprovalGrant approvalGrant,
                                 RuntimeAgentExecutionEventSink eventSink,
                                 RuntimeAgentExecutionCancellation cancellation) {
            this(agent, config, workflowTools, input, approvalGrant, eventSink, cancellation, null, List.of(),
                    TrustedPersonalMemoryContext.empty(), List.of(), List.of(), RuntimeEvalExecutionContext.none());
        }

        public SupervisorRequest(RuntimeAgentView agent,
                                 RuntimeAgentConfigSnapshot config,
                                 List<RuntimeAgentWorkflowToolSnapshot> workflowTools,
                                 Map<String, Object> input,
                                 PolicyApprovalGrant approvalGrant,
                                 RuntimeAgentExecutionEventSink eventSink) {
            this(agent, config, workflowTools, input, approvalGrant, eventSink,
                    RuntimeAgentExecutionCancellation.NOOP, null, List.of(), TrustedPersonalMemoryContext.empty(),
                    List.of(), List.of(), RuntimeEvalExecutionContext.none());
        }

        public SupervisorRequest(RuntimeAgentView agent,
                                 RuntimeAgentConfigSnapshot config,
                                 List<RuntimeAgentWorkflowToolSnapshot> workflowTools,
                                 Map<String, Object> input,
                                 PolicyApprovalGrant approvalGrant) {
            this(agent, config, workflowTools, input, approvalGrant, RuntimeAgentExecutionEventSink.NOOP,
                    RuntimeAgentExecutionCancellation.NOOP, null, List.of(), TrustedPersonalMemoryContext.empty(),
                    List.of(), List.of(), RuntimeEvalExecutionContext.none());
        }

        public SupervisorRequest(RuntimeAgentView agent,
                                 RuntimeAgentConfigSnapshot config,
                                 List<RuntimeAgentWorkflowToolSnapshot> workflowTools,
                                 Map<String, Object> input) {
            this(agent, config, workflowTools, input, null, RuntimeAgentExecutionEventSink.NOOP,
                    RuntimeAgentExecutionCancellation.NOOP, null, List.of(), TrustedPersonalMemoryContext.empty(),
                    List.of(), List.of(), RuntimeEvalExecutionContext.none());
        }
    }

    /** Immutable execution snapshot; Supervisor never receives the A2A persistence entity. */
    record RemoteAgentBinding(Long id,
                              Long principalId,
                              Long remoteAgentId,
                              Long remoteAgentRevisionId,
                              String remoteAgentKeySnapshot,
                              String toolName,
                              String descriptionSnapshot,
                              String allowedSkillIdsJson,
                              String outputModesJson,
                              String riskLevel,
                              String permissionKey,
                              Boolean enabled) {

        public Long getId() { return id; }
        public Long getPrincipalId() { return principalId; }
        public Long getRemoteAgentId() { return remoteAgentId; }
        public Long getRemoteAgentRevisionId() { return remoteAgentRevisionId; }
        public String getRemoteAgentKeySnapshot() { return remoteAgentKeySnapshot; }
        public String getToolName() { return toolName; }
        public String getDescriptionSnapshot() { return descriptionSnapshot; }
        public String getAllowedSkillIdsJson() { return allowedSkillIdsJson; }
        public String getOutputModesJson() { return outputModesJson; }
        public String getRiskLevel() { return riskLevel; }
        public String getPermissionKey() { return permissionKey; }
        public Boolean getEnabled() { return enabled; }
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
