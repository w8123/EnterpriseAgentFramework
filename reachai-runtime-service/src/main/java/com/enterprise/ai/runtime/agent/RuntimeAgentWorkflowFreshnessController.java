package com.enterprise.ai.runtime.agent;

import com.enterprise.ai.runtime.agent.RuntimeAgentWorkflowFreshnessViews.AgentWorkflowFreshnessView;
import com.enterprise.ai.runtime.agent.RuntimeAgentWorkflowFreshnessViews.PublishLatestWorkflowsRequest;
import com.enterprise.ai.runtime.agent.RuntimeAgentWorkflowFreshnessViews.PublishLatestWorkflowsResult;
import com.enterprise.ai.runtime.agent.RuntimeAgentWorkflowFreshnessViews.WorkflowAgentReferencesView;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** Pinned-vs-current Workflow versions of ACTIVE Agent configurations, plus the explicit refresh publish. */
@RestController
@RequiredArgsConstructor
public class RuntimeAgentWorkflowFreshnessController {

    private final RuntimeAgentWorkflowFreshnessService freshnessService;
    private final RuntimeAgentConfigService configService;

    @GetMapping("/api/agents/{agentId}/workflow-freshness")
    public ResponseEntity<AgentWorkflowFreshnessView> agentFreshness(@PathVariable String agentId) {
        return ResponseEntity.ok(freshnessService.agentFreshness(agentId));
    }

    @PostMapping("/api/agents/{agentId}/workflow-freshness/publish-latest")
    public ResponseEntity<PublishLatestWorkflowsResult> publishLatest(
            @PathVariable String agentId,
            @RequestBody(required = false) PublishLatestWorkflowsRequest request) {
        return ResponseEntity.ok(configService.publishLatestWorkflowVersions(
                agentId,
                request == null ? null : request.expectedActiveConfigVersionId(),
                request == null ? null : request.publishedBy()));
    }

    @GetMapping("/api/workflows/{workflowId}/agent-references")
    public ResponseEntity<WorkflowAgentReferencesView> workflowReferences(@PathVariable String workflowId) {
        return ResponseEntity.ok(freshnessService.workflowReferences(workflowId));
    }

    @ExceptionHandler(RuntimeAgentConfigConflictException.class)
    public ResponseEntity<Map<String, Object>> conflict(RuntimeAgentConfigConflictException conflict) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                "code", RuntimeAgentConfigConflictException.CODE,
                "message", conflict.getMessage()));
    }

    @ExceptionHandler(RuntimeAgentConfigService.HttpApiRiskDowngradeException.class)
    public ResponseEntity<Map<String, Object>> riskDowngrade(RuntimeAgentConfigService.HttpApiRiskDowngradeException rejected) {
        return ResponseEntity.badRequest().body(Map.of(
                "code", "HTTP_API_WORKFLOW_RISK_DOWNGRADE", "message", rejected.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> invalid(IllegalArgumentException invalid) {
        String message = invalid.getMessage() == null ? "Agent Workflow 版本更新请求无效" : invalid.getMessage();
        return ResponseEntity.badRequest().body(Map.of("code", "AGENT_WORKFLOW_FRESHNESS_INVALID", "message", message));
    }
}
