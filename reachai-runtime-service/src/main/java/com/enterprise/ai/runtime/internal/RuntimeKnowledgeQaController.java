package com.enterprise.ai.runtime.internal;

import com.enterprise.ai.runtime.internal.RuntimeKnowledgeQaWorkflowService.AttachKnowledgeRequest;
import com.enterprise.ai.runtime.internal.RuntimeKnowledgeQaWorkflowService.AttachView;
import com.enterprise.ai.runtime.internal.RuntimeKnowledgeQaWorkflowService.KnowledgeUsageView;
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

/** Knowledge base usage by Workflows, and the one-step knowledge Q&A attachment to an Agent. */
@RestController
@RequiredArgsConstructor
public class RuntimeKnowledgeQaController {

    private final RuntimeKnowledgeQaWorkflowService knowledgeQaService;

    @PostMapping("/api/agents/{agentId}/knowledge-qa")
    public ResponseEntity<AttachView> attach(@PathVariable String agentId,
                                             @RequestBody AttachKnowledgeRequest request) {
        return ResponseEntity.ok(knowledgeQaService.attach(agentId, request));
    }

    @GetMapping("/api/workflows/knowledge-usage")
    public ResponseEntity<List<KnowledgeUsageView>> usage(@RequestParam List<String> knowledgeBaseCodes) {
        return ResponseEntity.ok(knowledgeQaService.usage(knowledgeBaseCodes));
    }

    @ExceptionHandler(AiCodingAttachmentException.class)
    public ResponseEntity<Map<String, Object>> attachmentFailed(AiCodingAttachmentException failure) {
        return ResponseEntity.status(failure.status()).body(failure.toErrorBody());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> invalid(IllegalArgumentException invalid) {
        return ResponseEntity.badRequest().body(Map.of(
                "code", "KNOWLEDGE_QA_INVALID",
                "message", invalid.getMessage() == null ? "知识库接入 Agent 请求无效" : invalid.getMessage()));
    }
}
