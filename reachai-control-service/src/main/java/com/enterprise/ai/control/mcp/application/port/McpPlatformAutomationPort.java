package com.enterprise.ai.control.mcp.application.port;

import com.enterprise.ai.control.identity.PlatformAuthenticatedSession;
import org.springframework.http.ResponseEntity;

import java.util.Map;

/** Automation management through the console controller: the same permissions, project scope and Runtime revision checks. */
public interface McpPlatformAutomationPort {
    enum Operation { LIST, READINESS, GET, OCCURRENCES, CREATE, UPDATE, PAUSE, RESUME, ARCHIVE, RUN, RETRY, CANCEL }
    /** Owner status and body as the console receives them; Runtime failures carry only a stable code. */
    ResponseEntity<?> call(PlatformAuthenticatedSession actor, Operation operation, String automationKey, Long occurrenceId,
                           Map<String, Object> query, Map<String, Object> body);
}
