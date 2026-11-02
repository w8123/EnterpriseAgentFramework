package com.enterprise.ai.control.mcp.application.port;

import com.enterprise.ai.control.identity.PlatformAuthenticatedSession;
import org.springframework.http.ResponseEntity;

import java.util.Map;

/**
 * A2A Hub reads through the console's A2A management controllers, which check a2a-hub:read (payloads also need
 * a2a-hub:payload:read and are audited). Credentials expose fingerprints only, never key material.
 */
public interface McpPlatformA2aReadPort {
    enum Read {
        OVERVIEW, PUBLICATIONS, PUBLICATION, REMOTE_AGENTS, REMOTE_AGENT, TASKS, TASK, MESSAGE_PAYLOAD, ARTIFACT_PAYLOAD,
        PRINCIPALS, PRINCIPAL, TRUST_PROFILES, TRUST_PROFILE, CREDENTIALS, CREDENTIAL
    }
    ResponseEntity<?> read(PlatformAuthenticatedSession actor, Read read, String id, String secondId, Map<String, Object> query);
}
