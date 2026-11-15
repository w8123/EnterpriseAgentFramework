package com.enterprise.ai.runtime.internal;

import com.enterprise.ai.runtime.internal.RuntimeLearnedFlowService.CandidateView;
import com.enterprise.ai.runtime.internal.RuntimeLearnedFlowService.FlowRequest;
import com.enterprise.ai.runtime.internal.RuntimeLearnedFlowService.FlowView;
import com.enterprise.ai.runtime.workflow.aicoding.AiCodingAttachmentException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** Reasoning → fixed flow: repeated read-only requests of an Agent, their draft flows and enabling them. */
@RestController
@RequiredArgsConstructor
public class RuntimeLearnedFlowController {

    private final RuntimeLearnedFlowService service;

    @GetMapping("/api/agents/{agentId}/learned-flows")
    public ResponseEntity<List<CandidateView>> candidates(@PathVariable String agentId,
                                                          @RequestParam(required = false) Integer days) {
        return ResponseEntity.ok(service.candidates(agentId, days));
    }

    /** 所有助手里还没变成流程的重复只读请求，可按业务系统筛选。 */
    @GetMapping("/api/learned-flows/pending")
    public ResponseEntity<List<RuntimeLearnedFlowService.PendingView>> pending(
            @RequestParam(required = false) String projectCode, @RequestParam(required = false) Integer days) {
        return ResponseEntity.ok(service.pending(projectCode, days));
    }

    @PostMapping("/api/agents/{agentId}/learned-flows/draft")
    public ResponseEntity<FlowView> draft(@PathVariable String agentId, @RequestBody FlowRequest request) {
        return ResponseEntity.ok(service.draft(agentId, request));
    }

    @PostMapping("/api/agents/{agentId}/learned-flows/enable")
    public ResponseEntity<FlowView> enable(@PathVariable String agentId, @RequestBody FlowRequest request) {
        return ResponseEntity.ok(service.enable(agentId, request));
    }

    @ExceptionHandler(AiCodingAttachmentException.class)
    public ResponseEntity<Map<String, Object>> attachmentFailed(AiCodingAttachmentException failure) {
        return ResponseEntity.status(failure.status()).body(failure.toErrorBody());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> invalid(IllegalArgumentException invalid) {
        return ResponseEntity.badRequest().body(Map.of(
                "code", "LEARNED_FLOW_INVALID",
                "message", invalid.getMessage() == null ? "直达流程请求无效" : invalid.getMessage()));
    }
}
