package com.enterprise.ai.control.mcp.application.port;

import com.enterprise.ai.control.identity.PlatformAiConnectionService.ConnectionIdentity;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;

/** 业务能力改造 tools: create a retrofit task for oneself, and propose candidates for the developer to pick. */
public interface McpPlatformCapabilityRetrofitPort {
    ResponseEntity<?> start(ConnectionIdentity actor, String projectCode, String objective, String executorProvider);

    ResponseEntity<?> propose(ConnectionIdentity actor, String taskId, String summary, List<Map<String, Object>> candidates);
}
