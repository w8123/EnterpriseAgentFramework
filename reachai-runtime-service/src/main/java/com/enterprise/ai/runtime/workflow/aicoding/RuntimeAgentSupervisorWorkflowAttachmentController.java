package com.enterprise.ai.runtime.workflow.aicoding;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class RuntimeAgentSupervisorWorkflowAttachmentController {

    private final RuntimeAgentSupervisorWorkflowAttachmentService attachmentService;

    @PostMapping("/internal/runtime/projects/{projectId}/agent-supervisor/workflow-tools/attach")
    public ResponseEntity<?> attach(
            @PathVariable("projectId") Long projectId,
            @RequestBody RuntimeAgentSupervisorWorkflowAttachmentService.AttachRequest request) {
        try {
            return ResponseEntity.ok(attachmentService.attach(projectId, request));
        } catch (AiCodingAttachmentException ex) {
            return ResponseEntity.status(ex.status()).body(ex.toErrorBody());
        }
    }
}
