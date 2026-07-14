package com.enterprise.ai.runtime.interaction;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequiredArgsConstructor
public class RuntimeHumanApprovalController {

    private final RuntimeHumanApprovalService humanApprovalService;

    @GetMapping(path = "/api/runtime/interactions/human-approvals")
    public ResponseEntity<List<RuntimeHumanApprovalService.PendingHumanApprovalView>> humanApprovals(
            @RequestParam(required = false) String agentId,
            @RequestParam(required = false) String userId,
            @RequestParam(defaultValue = "50") int limit) {
        return ResponseEntity.ok(humanApprovalService.listPendingHumanApprovals(agentId, userId, limit));
    }

    @PostMapping(path = "/api/runtime/interactions/human-approvals/{interactionId}/submit")
    public ResponseEntity<RuntimeHumanApprovalService.AgentResultView> submitHumanApproval(
            @PathVariable String interactionId,
            @RequestBody RuntimeHumanApprovalService.SubmitRequest request) {
        return ResponseEntity.ok(humanApprovalService.submitHumanApproval(interactionId, request));
    }

    @DeleteMapping(path = "/api/runtime/interactions/human-approvals/{interactionId}")
    public ResponseEntity<RuntimeHumanApprovalService.AgentResultView> cancelHumanApproval(
            @PathVariable String interactionId,
            @RequestParam(required = false) String userId) {
        return ResponseEntity.ok(humanApprovalService.cancelHumanApproval(interactionId, userId));
    }
}
