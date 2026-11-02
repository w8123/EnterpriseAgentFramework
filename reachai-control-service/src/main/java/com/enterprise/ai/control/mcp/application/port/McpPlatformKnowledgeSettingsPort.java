package com.enterprise.ai.control.mcp.application.port;

import com.enterprise.ai.control.identity.PlatformAuthenticatedSession;
import org.springframework.http.ResponseEntity;
import java.util.Map;

public interface McpPlatformKnowledgeSettingsPort {
    ResponseEntity<?> context(PlatformAuthenticatedSession actor, String kbCode);
    ResponseEntity<?> preview(PlatformAuthenticatedSession actor, String kbCode, String revision, Map<String, Object> changes);
    ResponseEntity<?> save(PlatformAuthenticatedSession actor, String kbCode, String revision, Map<String, Object> changes);
}
