package com.enterprise.ai.control.mcp.application.port;

import org.springframework.http.ResponseEntity;

import java.util.Map;

/** Chunk edits through the knowledge console endpoints; callers check the resource write grant before calling. */
public interface McpPlatformKnowledgeChunkPort {
    enum Operation { UPDATE, TOGGLE, REEMBED }
    /** Unwrapped owner data on success; a bare 4xx on an owner rejection; a bare 503 when the result is not confirmed. */
    ResponseEntity<?> change(Operation operation, long chunkId, Map<String, Object> body);
}
