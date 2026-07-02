package com.enterprise.ai.runtime.compat;

import com.enterprise.ai.runtime.workflow.RuntimeWorkflowReleaseValidationResult;
import com.enterprise.ai.runtime.workflow.RuntimeWorkflowVersionEntity;
import com.enterprise.ai.runtime.workflow.RuntimeWorkflowVersionService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequiredArgsConstructor
public class RuntimeWorkflowVersionCompatibilityController {

    private final RuntimeWorkflowVersionService workflowVersionService;

    @GetMapping("/api/workflows/{workflowId}/versions")
    public ResponseEntity<List<RuntimeWorkflowVersionEntity>> list(@PathVariable String workflowId) {
        return ResponseEntity.ok(workflowVersionService.listVersions(workflowId));
    }

    @PostMapping("/api/workflows/{workflowId}/versions")
    public ResponseEntity<?> publish(@PathVariable String workflowId,
                                     @RequestBody RuntimeWorkflowVersionPublishRequest request) {
        return publishWorkflow(workflowId, request);
    }

    @PostMapping("/api/workflows/{workflowId}/versions/publish")
    public ResponseEntity<?> publishExplicit(@PathVariable String workflowId,
                                             @RequestBody RuntimeWorkflowVersionPublishRequest request) {
        return publishWorkflow(workflowId, request);
    }

    @PostMapping("/api/workflows/{workflowId}/versions/validate")
    public ResponseEntity<RuntimeWorkflowReleaseValidationResult> validate(@PathVariable String workflowId) {
        return ResponseEntity.ok(workflowVersionService.validateRelease(workflowId));
    }

    @PostMapping("/api/workflows/{workflowId}/versions/{versionId}/rollback")
    public ResponseEntity<RuntimeWorkflowVersionEntity> rollback(
            @PathVariable String workflowId,
            @PathVariable Long versionId,
            @RequestBody(required = false) RuntimeWorkflowVersionRollbackRequest request) {
        return ResponseEntity.ok(workflowVersionService.rollback(
                workflowId,
                versionId,
                request == null ? null : request.operator()));
    }

    private ResponseEntity<?> publishWorkflow(String workflowId, RuntimeWorkflowVersionPublishRequest request) {
        try {
            int rollout = request == null || request.rolloutPercent() == null ? 100 : request.rolloutPercent();
            return ResponseEntity.ok(workflowVersionService.publish(
                    workflowId,
                    request == null ? null : request.version(),
                    rollout,
                    request == null ? null : request.note(),
                    request == null ? null : request.publishedBy()));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(Map.of("message", ex.getMessage()));
        }
    }
}
