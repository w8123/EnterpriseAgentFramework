package com.enterprise.ai.agent.platform.control.controller;

import com.enterprise.ai.agent.aicoding.AiCodingContextCandidateSubmissionService;
import com.enterprise.ai.agent.platform.control.context.memory.ContextMemoryCandidateCreateRequest;
import com.enterprise.ai.agent.platform.control.context.memory.ContextMemoryCandidateQueryRequest;
import com.enterprise.ai.agent.platform.control.context.memory.ContextMemoryCandidateResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/ai-coding/projects/{projectId}/context-candidates")
@RequiredArgsConstructor
public class AiCodingContextCandidateController {

    private final AiCodingContextCandidateSubmissionService submissionService;

    @PostMapping
    public ResponseEntity<ContextMemoryCandidateResponse> createCandidate(
            @PathVariable Long projectId,
            @RequestBody(required = false) ContextMemoryCandidateCreateRequest request) {
        return ResponseEntity.ok(submissionService.createCandidate(projectId, request));
    }

    @PostMapping("/batch")
    public ResponseEntity<List<ContextMemoryCandidateResponse>> createCandidateBatch(
            @PathVariable Long projectId,
            @RequestBody(required = false) List<ContextMemoryCandidateCreateRequest> requests) {
        return ResponseEntity.ok(submissionService.createCandidateBatch(projectId, requests));
    }

    @GetMapping
    public ResponseEntity<List<ContextMemoryCandidateResponse>> listCandidates(
            @PathVariable Long projectId,
            ContextMemoryCandidateQueryRequest query) {
        return ResponseEntity.ok(submissionService.listCandidates(projectId, query));
    }
}
