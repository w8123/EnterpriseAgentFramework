package com.enterprise.ai.control.mcp.application.platform;

import com.enterprise.ai.control.identity.PlatformAiConnectionService.ConnectionIdentity;
import com.enterprise.ai.control.mcp.application.port.McpPlatformHttpApiAcceptPort;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

/**
 * Accepting an observed HTTP API only records it in the catalog against the source revision the caller read; calling
 * it still needs its own confirmation and tool permissions, so it runs directly. A lost result is never resent.
 */
@Service
@RequiredArgsConstructor
public class McpPlatformHttpApiAcceptService {
    private final McpPlatformHttpApiAcceptPort apis;

    public ResponseEntity<?> accept(ConnectionIdentity actor, long apiId, String expectedSourceSetRevision) {
        if (expectedSourceSetRevision == null || !expectedSourceSetRevision.matches("[a-f0-9]{64}")) {
            throw new IllegalArgumentException("expectedSourceSetRevision is invalid");
        }
        return PlatformAiDirectWrites.once(() -> apis.accept(actor.session(), apiId, expectedSourceSetRevision),
                "接纳结果未确认，请重新读取这个 API 再决定；不要直接重试。");
    }
}
