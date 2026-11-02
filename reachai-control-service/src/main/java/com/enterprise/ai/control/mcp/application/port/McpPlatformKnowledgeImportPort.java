package com.enterprise.ai.control.mcp.application.port;

import com.enterprise.ai.control.identity.PlatformAuthenticatedSession;
import org.springframework.http.ResponseEntity;

/** Document import jobs through the same console chain, resource scope check and audit as the human page. */
public interface McpPlatformKnowledgeImportPort {
    enum Operation { COMMIT, RETRY, CANCEL }
    /** Returns the owner job view (the envelope's data), or the owner status with no body. */
    ResponseEntity<?> job(PlatformAuthenticatedSession actor, String jobId, boolean includePreview);
    ResponseEntity<?> mutate(PlatformAuthenticatedSession actor, String jobId, Operation operation);
    /** Re-parses one uploaded file of an uncommitted import; returns the owner's data view. */
    ResponseEntity<?> reparse(PlatformAuthenticatedSession actor, String fileId);
    /** Submits an uploaded file exactly like the page upload; the job always waits for an explicit commit. */
    ResponseEntity<?> submit(PlatformAuthenticatedSession actor, String fileName, byte[] content, String knowledgeBaseCode,
                             String chunkStrategy, int chunkSize, int chunkOverlap);
}
