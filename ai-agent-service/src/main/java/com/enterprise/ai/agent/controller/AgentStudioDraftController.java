package com.enterprise.ai.agent.controller;

import com.enterprise.ai.agent.studio.WorkflowDraftGenerationRequest;
import com.enterprise.ai.agent.studio.WorkflowDraftGenerationResult;
import com.enterprise.ai.agent.studio.WorkflowDraftGenerationService;
import com.enterprise.ai.agent.studio.WorkflowDraftEditRequest;
import com.enterprise.ai.agent.studio.WorkflowDraftEditResult;
import com.enterprise.ai.agent.studio.WorkflowDraftEditService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/agent/studio")
@RequiredArgsConstructor
public class AgentStudioDraftController {

    private final WorkflowDraftGenerationService generationService;

    private final WorkflowDraftEditService editService;

    @PostMapping("/generate-draft")
    public ResponseEntity<WorkflowDraftGenerationResult> generateDraft(
            @RequestBody WorkflowDraftGenerationRequest request) {
        return ResponseEntity.ok(generationService.generate(request));
    }

    @PostMapping("/edit-draft")
    public ResponseEntity<WorkflowDraftEditResult> editDraft(
            @RequestBody WorkflowDraftEditRequest request) {
        return ResponseEntity.ok(editService.edit(request));
    }
}
