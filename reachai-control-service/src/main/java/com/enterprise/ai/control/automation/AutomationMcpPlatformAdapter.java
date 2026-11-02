package com.enterprise.ai.control.automation;

import com.enterprise.ai.control.identity.PlatformAuthenticatedSession;
import com.enterprise.ai.control.identity.PlatformDelegatedRequestScope;
import com.enterprise.ai.control.mcp.application.port.McpPlatformAutomationPort;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
@RequiredArgsConstructor
final class AutomationMcpPlatformAdapter implements McpPlatformAutomationPort {
    private final ControlAutomationController automations;
    private final PlatformDelegatedRequestScope scope;

    @Override
    public ResponseEntity<?> call(PlatformAuthenticatedSession actor, Operation operation, String key, Long occurrenceId,
                                  Map<String, Object> query, Map<String, Object> body) {
        return scope.run(actor, request -> switch (operation) {
            case LIST -> automations.list(request, null, (String) query.get("projectCode"), (String) query.get("status"),
                    (String) query.get("keyword"), (Integer) query.getOrDefault("limit", 200));
            case READINESS -> automations.readiness(request);
            case GET -> automations.get(request, key);
            case OCCURRENCES -> automations.occurrences(request, key, (String) query.get("status"), (Integer) query.getOrDefault("limit", 200));
            case CREATE -> automations.create(request, body);
            case UPDATE -> automations.update(request, key, body);
            case PAUSE -> automations.pause(request, key, body);
            case RESUME -> automations.resume(request, key, body);
            case ARCHIVE -> automations.archive(request, key, ((Number) body.get("expectedRevision")).longValue());
            case RUN -> automations.runNow(request, key);
            case RETRY -> automations.retry(request, key, occurrenceId);
            case CANCEL -> automations.cancelOccurrence(request, key, occurrenceId, body);
        });
    }
}
