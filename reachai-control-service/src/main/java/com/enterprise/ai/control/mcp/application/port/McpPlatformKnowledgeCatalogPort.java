package com.enterprise.ai.control.mcp.application.port;

import com.enterprise.ai.control.identity.PlatformAuthenticatedSession;
import org.springframework.http.ResponseEntity;
import java.util.Map;

public interface McpPlatformKnowledgeCatalogPort {
    enum Operation { BASES, FILES, STATS, DASHBOARD, CHUNKS, HIT_LOGS, FILE_CHUNKS, DEPENDENCIES }
    ResponseEntity<?> read(PlatformAuthenticatedSession actor, Operation operation, Map<String, Object> parameters);
    /** {resource, chunks} of one file, only when the caller holds knowledge write on that owner-signed resource. */
    ResponseEntity<?> fileChunksForWrite(PlatformAuthenticatedSession actor, String fileId);
    /** The owner-signed scope of one knowledge base, only when the caller holds knowledge write on it. */
    ResponseEntity<?> baseForWrite(PlatformAuthenticatedSession actor, String kbCode);
}
