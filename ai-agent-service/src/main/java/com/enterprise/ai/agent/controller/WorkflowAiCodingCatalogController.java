package com.enterprise.ai.agent.controller;

import com.enterprise.ai.agent.workflow.aicoding.WorkflowAiCodingContextResponse;
import com.enterprise.ai.agent.workflow.aicoding.WorkflowAiCodingCreateRequest;
import com.enterprise.ai.agent.workflow.aicoding.WorkflowAiCodingService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/workflows/ai-coding")
@RequiredArgsConstructor
public class WorkflowAiCodingCatalogController {

    private final WorkflowAiCodingService aiCodingService;

    @PostMapping("/workflows")
    public ResponseEntity<WorkflowAiCodingContextResponse> create(
            @RequestBody(required = false) WorkflowAiCodingCreateRequest request) {
        return ResponseEntity.ok(aiCodingService.create(request));
    }
}
