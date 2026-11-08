package com.enterprise.ai.control.mcp.application.port;

import com.enterprise.ai.control.identity.PlatformAiConnectionService;
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

    /** What the coding agent itself does on a task it claimed through its platform AI connection. */
    enum Agent { CONTEXT, HEARTBEAT, EVENTS, EVENT, QUESTIONS, ASK, ARTIFACT, VERIFY }

    /** Claims a task with a person-issued one-time code; the claim is bound to this connection. */
    ResponseEntity<?> claim(PlatformAiConnectionService.ConnectionIdentity identity, String taskId, String claimCode);

    /** Claims a task the same account just created over MCP, without a code (creator only). */
    ResponseEntity<?> claimAsCreator(PlatformAiConnectionService.ConnectionIdentity identity, String taskId);

    /** Runs a task protocol step as the connection that claimed the task; {@code body} is the protocol command. */
    ResponseEntity<?> agent(PlatformAiConnectionService.ConnectionIdentity identity, String taskId, Agent step, Map<String, Object> body);
}
