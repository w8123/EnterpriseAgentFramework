package com.enterprise.ai.control.mcp.application.platform;

import com.enterprise.ai.control.identity.PlatformAiConnectionService.ConnectionIdentity;
import com.enterprise.ai.control.mcp.application.port.McpPlatformDebugSessionPort;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

/**
 * AI can follow and stop a Studio debug session. Starting one or answering its interactions stays with people: a
 * debug session runs an unpublished graph without the read-only restriction, and its interaction steps are the
 * human confirmations inside that graph. AI tests drafts with the read-only trial instead.
 */
@Service
@RequiredArgsConstructor
public class McpPlatformDebugSessionService {
    private final McpPlatformDebugSessionPort sessions;

    public ResponseEntity<?> get(ConnectionIdentity actor, String sessionId, String creationKey) {
        return sessionId != null ? sessions.get(actor.session(), sessionId) : sessions.getByCreationKey(actor.session(), creationKey);
    }

    public ResponseEntity<?> cancel(ConnectionIdentity actor, String sessionId) {
        return PlatformAiDirectWrites.once(() -> sessions.cancel(actor.session(), sessionId),
                "取消结果未确认，请用 reachai_workflow_debug_session_get 重新读取会话。");
    }
}
