package com.enterprise.ai.runtime.internal;

import com.enterprise.ai.runtime.workflow.RuntimeTraceWorkflowCandidateDraftService;
import com.enterprise.ai.runtime.workflow.RuntimeTraceWorkflowCandidateDraftService.DraftRequest;
import com.enterprise.ai.runtime.workflow.aicoding.RuntimeWorkflowAiCodingService.ContextView;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class RuntimeTraceWorkflowCandidateInternalController {

    private final RuntimeTraceWorkflowCandidateDraftService service;

    @PostMapping("/internal/runtime/runops/workflow-candidates/drafts")
    public ResponseEntity<ContextView> createOrReplace(
            @RequestBody DraftRequest request) {
        return ResponseEntity.ok(service.createOrReplace(request));
    }
}
