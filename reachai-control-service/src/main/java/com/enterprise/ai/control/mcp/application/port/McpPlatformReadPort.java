package com.enterprise.ai.control.mcp.application.port;

import com.enterprise.ai.control.identity.PlatformAuthenticatedSession;
import org.springframework.http.ResponseEntity;

/** Operations backed by the same platform resources and checks as the human console. */
public interface McpPlatformReadPort {
    ResponseEntity<?> listWorkflows(PlatformAuthenticatedSession actor, Long projectId, String projectCode,
                                    String workflowKind, String status);
    ResponseEntity<?> getWorkflow(PlatformAuthenticatedSession actor, String workflowId);
    ResponseEntity<?> listWorkflowVersions(PlatformAuthenticatedSession actor, String workflowId);
    ResponseEntity<?> getWorkflowWorkingCopy(PlatformAuthenticatedSession actor, String workflowId);
    ResponseEntity<?> workflowNodeTypes(PlatformAuthenticatedSession actor);
    ResponseEntity<?> listAgents(PlatformAuthenticatedSession actor, Long projectId, String projectCode);
    ResponseEntity<?> getAgent(PlatformAuthenticatedSession actor, String agentId);
    ResponseEntity<?> listAgentConfigVersions(PlatformAuthenticatedSession actor, String agentId);
    ResponseEntity<?> getAgentWorkflowFreshness(PlatformAuthenticatedSession actor, String agentId);
    ResponseEntity<?> listRunOps(PlatformAuthenticatedSession actor, String projectCode, String status,
                                 String agentId, String keyword, int limit, int days);
    ResponseEntity<?> getRunOpsDetail(PlatformAuthenticatedSession actor, String traceId);
    ResponseEntity<?> getTrace(PlatformAuthenticatedSession actor, String traceId);
    ResponseEntity<?> listHumanApprovals(PlatformAuthenticatedSession actor, String agentId, String userId, int limit);
    ResponseEntity<?> searchWorkflows(PlatformAuthenticatedSession actor, Long projectId, String projectCode, String workflowKind,
                                      String status, String keyword, int current, int size);
    ResponseEntity<?> validateWorkflowRelease(PlatformAuthenticatedSession actor, String workflowId);
    ResponseEntity<?> validateWorkflowRuntime(PlatformAuthenticatedSession actor, java.util.Map<String, Object> body);
    ResponseEntity<?> listWorkflowAgentReferences(PlatformAuthenticatedSession actor, String workflowId);
    ResponseEntity<?> workflowKnowledgeUsage(PlatformAuthenticatedSession actor, java.util.List<String> knowledgeBaseCodes);
    ResponseEntity<?> agentStatistics(PlatformAuthenticatedSession actor, Long projectId, String projectCode);
    ResponseEntity<?> evalReadiness(PlatformAuthenticatedSession actor);
    ResponseEntity<?> listEvalDatasets(PlatformAuthenticatedSession actor, String agentId);
    ResponseEntity<?> getEvalDataset(PlatformAuthenticatedSession actor, long datasetId);
    ResponseEntity<?> listEvalExperiments(PlatformAuthenticatedSession actor, String agentId);
    ResponseEntity<?> getEvalExperiment(PlatformAuthenticatedSession actor, long experimentId);
}
