package com.enterprise.ai.control.runtime;

import com.enterprise.ai.control.identity.PlatformAuthenticatedSession;
import com.enterprise.ai.control.mcp.application.port.McpPlatformReadPort;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

/** Reuses the console's owner/project checks while preserving the verified actor for internal calls. */
@Component
@RequiredArgsConstructor
final class RuntimeMcpPlatformReadAdapter implements McpPlatformReadPort {
    private final ControlRuntimePublicController runtime;
    private final RuntimeMcpActorScope actorScope;

    @Override
    public ResponseEntity<?> listWorkflows(PlatformAuthenticatedSession actor, Long projectId, String projectCode,
                                          String workflowKind, String status) {
        return actorScope.run(actor, () -> runtime.listWorkflows(projectId, projectCode, workflowKind, null, status));
    }

    @Override
    public ResponseEntity<?> getWorkflow(PlatformAuthenticatedSession actor, String workflowId) {
        return actorScope.run(actor, () -> runtime.getWorkflow(workflowId));
    }

    @Override
    public ResponseEntity<?> getWorkflowWorkingCopy(PlatformAuthenticatedSession actor, String workflowId) {
        return actorScope.run(actor, () -> runtime.workflowWorkingCopy(workflowId));
    }

    @Override
    public ResponseEntity<?> listWorkflowVersions(PlatformAuthenticatedSession actor, String workflowId) {
        return actorScope.run(actor, () -> runtime.listWorkflowVersions(workflowId));
    }

    @Override
    public ResponseEntity<?> validateWorkflowRuntime(PlatformAuthenticatedSession actor, java.util.Map<String, Object> body) {
        return actorScope.run(actor, () -> runtime.validateWorkflowRuntime(body));
    }

    @Override
    public ResponseEntity<?> workflowNodeTypes(PlatformAuthenticatedSession actor) {
        return actorScope.run(actor, runtime::graphNodeTypes);
    }

    @Override
    public ResponseEntity<?> listAgents(PlatformAuthenticatedSession actor, Long projectId, String projectCode) {
        return actorScope.run(actor, () -> runtime.listAgents(projectId, projectCode));
    }

    @Override
    public ResponseEntity<?> getAgent(PlatformAuthenticatedSession actor, String agentId) {
        return actorScope.run(actor, () -> runtime.getAgent(agentId));
    }

    @Override
    public ResponseEntity<?> listAgentConfigVersions(PlatformAuthenticatedSession actor, String agentId) {
        return actorScope.run(actor, () -> runtime.platformAgentConfigVersions(agentId));
    }

    @Override
    public ResponseEntity<?> getAgentWorkflowFreshness(PlatformAuthenticatedSession actor, String agentId) {
        return actorScope.run(actor, () -> runtime.getAgentWorkflowFreshness(agentId));
    }

    @Override
    public ResponseEntity<?> listRunOps(PlatformAuthenticatedSession actor, String projectCode, String status,
                                        String agentId, String keyword, int limit, int days) {
        return actorScope.run(actor, () -> runtime.runOpsRecent(projectCode, status, null, null, agentId, null, keyword, limit, days));
    }

    @Override
    public ResponseEntity<?> getRunOpsDetail(PlatformAuthenticatedSession actor, String traceId) {
        return actorScope.run(actor, () -> runtime.runOpsDetail(traceId));
    }

    @Override
    public ResponseEntity<?> getTrace(PlatformAuthenticatedSession actor, String traceId) {
        return actorScope.run(actor, () -> runtime.getTrace(traceId));
    }

    @Override
    public ResponseEntity<?> listHumanApprovals(PlatformAuthenticatedSession actor, String agentId, String userId, int limit) {
        return actorScope.run(actor, () -> runtime.listHumanApprovals(agentId, userId, limit));
    }

    @Override
    public ResponseEntity<?> searchWorkflows(PlatformAuthenticatedSession actor, Long projectId, String projectCode,
                                             String workflowKind, String status, String keyword, int current, int size) {
        return actorScope.run(actor, () -> runtime.searchWorkflows(projectId, projectCode, workflowKind, null, status, keyword, current, size));
    }

    @Override
    public ResponseEntity<?> validateWorkflowRelease(PlatformAuthenticatedSession actor, String workflowId) {
        return actorScope.run(actor, () -> runtime.validateWorkflowVersion(workflowId));
    }

    @Override
    public ResponseEntity<?> listWorkflowAgentReferences(PlatformAuthenticatedSession actor, String workflowId) {
        return actorScope.run(actor, () -> runtime.listWorkflowAgentReferences(workflowId));
    }

    @Override
    public ResponseEntity<?> workflowKnowledgeUsage(PlatformAuthenticatedSession actor, java.util.List<String> knowledgeBaseCodes) {
        return actorScope.run(actor, () -> runtime.listKnowledgeUsage(knowledgeBaseCodes));
    }

    @Override
    public ResponseEntity<?> agentStatistics(PlatformAuthenticatedSession actor, Long projectId, String projectCode) {
        return actorScope.run(actor, () -> runtime.getAgentStatistics(projectId, projectCode));
    }

    @Override
    public ResponseEntity<?> evalReadiness(PlatformAuthenticatedSession actor) {
        return actorScope.run(actor, runtime::evalOpsReadiness);
    }

    @Override
    public ResponseEntity<?> listEvalDatasets(PlatformAuthenticatedSession actor, String agentId) {
        return actorScope.run(actor, () -> runtime.listEvalOpsDatasets(agentId));
    }

    @Override
    public ResponseEntity<?> getEvalDataset(PlatformAuthenticatedSession actor, long datasetId) {
        return actorScope.run(actor, () -> runtime.getEvalOpsDataset(datasetId));
    }

    @Override
    public ResponseEntity<?> listEvalExperiments(PlatformAuthenticatedSession actor, String agentId) {
        return actorScope.run(actor, () -> runtime.listEvalOpsExperiments(agentId));
    }

    @Override
    public ResponseEntity<?> getEvalExperiment(PlatformAuthenticatedSession actor, long experimentId) {
        return actorScope.run(actor, () -> runtime.getEvalOpsExperiment(experimentId));
    }
}
