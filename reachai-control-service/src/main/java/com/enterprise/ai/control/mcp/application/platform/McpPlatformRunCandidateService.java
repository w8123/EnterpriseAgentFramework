package com.enterprise.ai.control.mcp.application.platform;

import com.enterprise.ai.control.identity.PlatformAiConnectionService.ConnectionIdentity;
import com.enterprise.ai.control.mcp.application.port.McpPlatformRunCandidatePort;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

/**
 * Creating the candidate task only queues an AI coding task whose output is a DRAFT Workflow (never published or
 * bound), and the same run reuses an existing task, so it runs directly.
 */
@Service
@RequiredArgsConstructor
public class McpPlatformRunCandidateService {
    private final McpPlatformRunCandidatePort candidates;

    public ResponseEntity<?> eligibility(ConnectionIdentity actor, String traceId) {
        return candidates.eligibility(actor.session(), traceId);
    }

    public ResponseEntity<?> create(ConnectionIdentity actor, String traceId, String executorProvider) {
        return PlatformAiDirectWrites.once(() -> candidates.create(actor.session(), traceId, executorProvider),
                "候选任务是否已创建未确认，请先用 reachai_ai_coding_tasks 查看；不要直接重试。");
    }
}
