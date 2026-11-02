package com.enterprise.ai.control.mcp.application.port;

import com.enterprise.ai.control.identity.PlatformAuthenticatedSession;
import org.springframework.http.ResponseEntity;

import java.util.Map;

/** Knowledge operations with the same actor and global authorization as the console. */
public interface McpPlatformKnowledgePort {
    ResponseEntity<?> listBusinessIndexes(PlatformAuthenticatedSession actor);
    ResponseEntity<?> getBusinessIndex(PlatformAuthenticatedSession actor, String indexCode);
    ResponseEntity<?> businessIndexStats(PlatformAuthenticatedSession actor, String indexCode);
    ResponseEntity<?> searchBusinessIndex(PlatformAuthenticatedSession actor, String indexCode, Map<String, Object> body);

    enum IndexChange { CREATE, UPDATE, DELETE, REBUILD }
    /** Writes need global knowledge:write, exactly as the console's business index page. */
    ResponseEntity<?> changeBusinessIndex(PlatformAuthenticatedSession actor, IndexChange change, String indexCode, Map<String, Object> body);
}
