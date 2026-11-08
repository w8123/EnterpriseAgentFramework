package com.enterprise.ai.control.mcp.application.platform;

import com.enterprise.ai.control.identity.PlatformAiConnectionService.ConnectionIdentity;
import com.enterprise.ai.control.mcp.application.port.McpPlatformCapabilityRetrofitPort;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * A developer's own coding tool may start a retrofit task for that same developer and hand it the candidate
 * list; both only record work. Choosing what to open stays with the developer (on the page, or by saying so
 * in the tool), and accepting capabilities or publishing them to employees stays with people.
 */
@Service
@RequiredArgsConstructor
public class McpPlatformCapabilityRetrofitService {
    private final McpPlatformCapabilityRetrofitPort retrofit;

    public ResponseEntity<?> start(ConnectionIdentity actor, String projectCode, String objective, String executorProvider) {
        return PlatformAiDirectWrites.once(() -> retrofit.start(actor, projectCode, objective, executorProvider),
                "改造任务是否已建好未确认，请先用 reachai_ai_coding_tasks（taskKind=CAPABILITY_RETROFIT）查看；不要直接重试。");
    }

    public ResponseEntity<?> propose(ConnectionIdentity actor, String taskId, String summary, List<Map<String, Object>> candidates) {
        return PlatformAiDirectWrites.once(() -> retrofit.propose(actor, taskId, summary, candidates),
                "候选清单是否已提交未确认，请先读取任务问题再决定；不要直接重试。");
    }
}
