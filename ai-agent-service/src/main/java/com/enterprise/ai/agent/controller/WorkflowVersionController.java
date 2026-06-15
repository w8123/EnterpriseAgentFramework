package com.enterprise.ai.agent.controller;

import com.enterprise.ai.agent.agent.AgentReleaseValidationResult;
import com.enterprise.ai.agent.workflow.WorkflowVersionEntity;
import com.enterprise.ai.agent.workflow.WorkflowVersionService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/workflows/{workflowId}/versions")
@RequiredArgsConstructor
public class WorkflowVersionController {

    private final WorkflowVersionService versionService;

    @GetMapping
    public ResponseEntity<List<WorkflowVersionEntity>> list(@PathVariable String workflowId) {
        return ResponseEntity.ok(versionService.listVersions(workflowId));
    }

    @PostMapping
    public ResponseEntity<WorkflowVersionEntity> publish(@PathVariable String workflowId,
                                                         @RequestBody PublishRequest request) {
        return ResponseEntity.ok(publishWorkflow(workflowId, request));
    }

    @PostMapping("/publish")
    public ResponseEntity<WorkflowVersionEntity> publishExplicit(@PathVariable String workflowId,
                                                                 @RequestBody PublishRequest request) {
        return ResponseEntity.ok(publishWorkflow(workflowId, request));
    }

    @PostMapping("/validate")
    public ResponseEntity<AgentReleaseValidationResult> validate(@PathVariable String workflowId) {
        return ResponseEntity.ok(versionService.validateRelease(workflowId));
    }

    private WorkflowVersionEntity publishWorkflow(String workflowId, PublishRequest request) {
        int rollout = request.getRolloutPercent() == null ? 100 : request.getRolloutPercent();
        return versionService.publish(
                workflowId,
                request.getVersion(),
                rollout,
                request.getNote(),
                request.getPublishedBy());
    }

    @PostMapping("/{versionId}/rollback")
    public ResponseEntity<WorkflowVersionEntity> rollback(@PathVariable String workflowId,
                                                          @PathVariable Long versionId,
                                                          @RequestBody(required = false) RollbackRequest request) {
        return ResponseEntity.ok(versionService.rollback(
                workflowId,
                versionId,
                request == null ? null : request.getOperator()));
    }

    @Data
    public static class PublishRequest {
        private String version;
        private Integer rolloutPercent;
        private String note;
        private String publishedBy;
    }

    @Data
    public static class RollbackRequest {
        private String operator;
    }
}
