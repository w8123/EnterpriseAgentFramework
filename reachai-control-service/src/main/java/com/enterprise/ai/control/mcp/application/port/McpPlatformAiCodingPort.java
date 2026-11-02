package com.enterprise.ai.control.mcp.application.port;

import com.enterprise.ai.control.identity.PlatformAuthenticatedSession;
import org.springframework.http.ResponseEntity;

import java.util.Map;

/** AI coding console tasks through the console controller, which checks each task against its own project. */
public interface McpPlatformAiCodingPort {
    enum Read { DETAIL, MANAGED_EXECUTION, ARTIFACTS, ACCEPTANCE_READINESS }
    ResponseEntity<?> list(PlatformAuthenticatedSession actor, Long projectId, String projectCode, String taskKind, String executionStatus, int limit);
    ResponseEntity<?> read(PlatformAuthenticatedSession actor, String taskId, Read read);
    ResponseEntity<?> create(PlatformAuthenticatedSession actor, Map<String, Object> command);
    ResponseEntity<?> cancel(PlatformAuthenticatedSession actor, String taskId);
    ResponseEntity<?> answer(PlatformAuthenticatedSession actor, String taskId, String questionId, String answer);
}
