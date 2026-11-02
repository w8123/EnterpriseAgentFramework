package com.enterprise.ai.control.platform;

import com.enterprise.ai.control.identity.PlatformAuthenticatedSession;
import com.enterprise.ai.control.identity.PlatformDelegatedRequestScope;
import com.enterprise.ai.control.mcp.api.management.McpPlatformConversationReader;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

import java.util.Map;

/** Platform AI reads of run center conversations through the same controller and project checks as the console. */
@Component
@RequiredArgsConstructor
class PlatformConversationMcpReader implements McpPlatformConversationReader {
    private final PlatformRunCenterConversationController conversations;
    private final PlatformDelegatedRequestScope scope;

    @Override
    public ResponseEntity<?> read(PlatformAuthenticatedSession actor, String sessionId, Map<String, Object> query) {
        return scope.run(actor, request -> ResponseEntity.ok(sessionId != null ? conversations.detail(request, sessionId)
                : conversations.list(request, (String) query.get("projectCode"), (String) query.get("agentId"), (String) query.get("keyword"),
                        Boolean.TRUE.equals(query.get("problemsOnly")), (Integer) query.get("days"), (Integer) query.get("limit"))));
    }
}
