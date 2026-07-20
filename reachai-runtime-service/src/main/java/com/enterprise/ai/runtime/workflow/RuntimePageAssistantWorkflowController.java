package com.enterprise.ai.runtime.workflow;

import com.enterprise.ai.runtime.workflow.aicoding.AiCodingAttachmentException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class RuntimePageAssistantWorkflowController {

    private final RuntimePageAssistantWorkflowAttachmentService attachmentService;

    @PostMapping("/api/workflows/{id}/page-assistant/attach-tool")
    public ResponseEntity<?> attachPageAssistantWorkflowTool(
            @PathVariable String id,
            @RequestBody RuntimePageAssistantWorkflowAttachRequest request) {
        try {
            return ResponseEntity.ok(attachmentService.attachPublishedPageWorkflow(id, request));
        } catch (AiCodingAttachmentException ex) {
            return ResponseEntity.status(ex.status()).body(ex.toErrorBody());
        }
    }
}
