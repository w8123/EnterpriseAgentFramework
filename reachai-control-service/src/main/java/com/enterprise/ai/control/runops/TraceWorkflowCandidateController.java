package com.enterprise.ai.control.runops;

import com.enterprise.ai.control.runops.TraceWorkflowCandidateApplicationService.CandidateTaskView;
import com.enterprise.ai.control.runops.TraceWorkflowCandidateApplicationService.CreateCandidateTaskRequest;
import com.enterprise.ai.control.runops.TraceWorkflowCandidateEligibility.EligibilityView;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/runops/traces/{traceId}/workflow-candidate")
@RequiredArgsConstructor
public class TraceWorkflowCandidateController {

    private final TraceWorkflowCandidateApplicationService service;

    @GetMapping("/eligibility")
    public ResponseEntity<EligibilityView> eligibility(
            @PathVariable String traceId) {
        return ResponseEntity.ok(service.eligibility(traceId));
    }

    @PostMapping("/tasks")
    public ResponseEntity<CandidateTaskView> createTask(
            @PathVariable String traceId,
            @RequestBody(required = false) CreateCandidateTaskRequest request) {
        return ResponseEntity.ok(service.createOrReuse(traceId, request));
    }
}
