package com.enterprise.ai.control.mcp.application.port;

import com.enterprise.ai.control.identity.PlatformAuthenticatedSession;
import org.springframework.http.ResponseEntity;

import java.util.Map;

/** Business system (scan project) reads, scan refreshes and configuration writes through the console proxy, authorized by the same console path policy. */
public interface McpPlatformBusinessSystemReadPort {
    enum Operation { PROJECT, OPERATION_BLOCKERS, TOOLS, TOOL, SOURCES, SENSITIVE_DATA_STATUS }
    enum Action { RESCAN, RECONCILE_TOOLS, SDK_ACCESS_CHECK, SDK_SYNC_SCAN, TOOL_RESCAN_FROM_SOURCE, SENSITIVE_DATA_SCAN }
    ResponseEntity<?> read(PlatformAuthenticatedSession actor, Operation operation, long projectId, Long scanToolId, Map<String, String> query);
    /** Refreshes derived scan data through the console endpoint; owner guards (blockers, stale results) still apply. */
    ResponseEntity<?> act(PlatformAuthenticatedSession actor, Action action, long projectId, Long scanToolId, Map<String, String> query);

    /** Configuration writes; callers gate them behind owner approval. */
    enum Change { CREATE, UPDATE, SCAN_SETTINGS, REGISTRY_CREDENTIAL, DELETE }
    /** Whether the console path policy admits this change for the delegate, so no approval is prepared for a refused write. */
    boolean admits(PlatformAuthenticatedSession actor, Change change, Long projectId);
    /** Dispatches once; owner 409 becomes BUSINESS_SYSTEM_OPERATION_BLOCKED, other 4xx a bare status, a lost or 5xx result a bare 5xx. */
    ResponseEntity<?> change(PlatformAuthenticatedSession actor, Change change, Long projectId, Object body);

    /**
     * Adds or updates HTTP APIs the caller's AI tool found in code (manual source). They land as
     * not-yet-accepted APIs; a person still accepts each one before it can be called.
     */
    default ResponseEntity<?> uploadHttpApis(PlatformAuthenticatedSession actor, long projectId, java.util.List<?> apis) {
        throw new UnsupportedOperationException("HTTP API upload is not available");
    }
}
