package com.enterprise.ai.runtime.a2a;

import com.enterprise.ai.runtime.execution.RuntimeAgentExecutionEventSink;
import com.enterprise.ai.runtime.execution.RuntimeAgentExecutionCancellation;
import com.enterprise.ai.runtime.execution.RuntimePublishedAgentExecutionPort;
import com.enterprise.ai.runtime.identity.WorkflowExecutionIdentity;
import com.enterprise.ai.runtime.internalauth.VerifiedInternalServiceAuth;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.ExceptionHandler;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import static com.enterprise.ai.common.internalauth.InternalServiceAuthHeaders.IDENTITY_SOURCE_A2A_REMOTE_AGENT;

/** Dedicated, HMAC-authenticated Control→Runtime contract for inbound A2A Tasks. */
@RestController
public class RuntimeA2aExecutionInternalController {

    public static final String EXECUTE_PATH = "/internal/runtime/a2a/executions";
    public static final String CANCEL_PATH_PREFIX = "/internal/runtime/a2a/executions/";

    private final RuntimePublishedAgentExecutionPort executionService;
    private final RuntimeA2aExecutionRegistry registry;

    public RuntimeA2aExecutionInternalController(
            RuntimePublishedAgentExecutionPort executionService,
            RuntimeA2aExecutionRegistry registry) {
        this.executionService = executionService;
        this.registry = registry;
    }

    @PostMapping(EXECUTE_PATH)
    public ResponseEntity<Map<String, Object>> execute(
            HttpServletRequest httpRequest,
            @RequestBody A2aRuntimeExecuteRequest request) {
        VerifiedInternalServiceAuth auth = requireA2aAuth(httpRequest);
        if (auth == null) {
            return unauthorized();
        }
        String executionId = identifier(request == null ? null : request.executionId(), "executionId");
        String taskId = identifier(request.taskId(), "taskId");
        String contextId = identifier(request.contextId(), "contextId");
        String agentId = identifier(request.agentId(), "agentId");
        if (request.agentConfigVersionId() == null || request.agentConfigVersionId() <= 0) {
            return badRequest("A2A_RUNTIME_CONFIG_VERSION_REQUIRED",
                    "agentConfigVersionId must be positive");
        }
        if (!StringUtils.hasText(request.message())) {
            return badRequest("A2A_RUNTIME_MESSAGE_REQUIRED", "message is required");
        }

        RuntimeA2aExecutionRegistry.StartResult start;
        try {
            start = registry.start(executionId, auth.identityTenantId(), auth.identityUserId());
        } catch (RuntimeA2aExecutionRegistry.RegistryException conflict) {
            int status = "A2A_RUNTIME_EXECUTION_FORBIDDEN".equals(conflict.code()) ? 403 : 409;
            return ResponseEntity.status(status).body(error(conflict.code(), conflict.getMessage()));
        }
        RuntimeAgentExecutionCancellation cancellation = start.cancellation();
        try {
            if (start.preCancelled()) {
                return ResponseEntity.ok(cancelled(executionId));
            }
            WorkflowExecutionIdentity identity = WorkflowExecutionIdentity.fromA2aRemoteAgent(
                    auth.identityTenantId(), null, null, auth.identityUserId());
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("agentId", agentId);
            // STANDARD Guard consumes tenantId from the server-owned policy input,
            // not the protocol Message metadata. Preserve only the signed tenant.
            if (StringUtils.hasText(auth.identityTenantId())) {
                body.put("tenantId", auth.identityTenantId().trim());
            }
            body.put("sessionId", contextId);
            body.put("message", request.message().trim());
            body.put("traceId", executionId);
            body.put("idempotencyKey", executionId);
            body.put("intentHint", "A2A_MESSAGE_SEND");
            body.put("entryType", "A2A");
            if (StringUtils.hasText(request.interactionId())) {
                body.put("interactionId", identifier(request.interactionId(), "interactionId"));
            }
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("channel", "A2A");
            metadata.put("a2aTaskId", taskId);
            metadata.put("a2aExecutionId", executionId);
            putSafe(metadata, "a2aPrincipalType", request.principalType());
            putSafe(metadata, "a2aTrustLevel", request.trustLevel());
            metadata.put("a2aScopes", safeScopes(request.scopes()));
            body.put("metadata", metadata);
            Map<String, Object> result = executionService.executePublishedConfig(
                    agentId, request.agentConfigVersionId(), body, false,
                    RuntimeAgentExecutionEventSink.NOOP,
                    cancellation, identity);
            return ResponseEntity.ok(result == null ? Map.of("success", false) : result);
        } finally {
            registry.finish(executionId, cancellation);
        }
    }

    @PostMapping(CANCEL_PATH_PREFIX + "{executionId}:cancel")
    public ResponseEntity<Map<String, Object>> cancel(
            HttpServletRequest httpRequest,
            @PathVariable String executionId) {
        VerifiedInternalServiceAuth auth = requireA2aAuth(httpRequest);
        if (auth == null) {
            return unauthorized();
        }
        try {
            RuntimeA2aExecutionRegistry.CancelResult result = registry.cancel(
                    identifier(executionId, "executionId"),
                    auth.identityTenantId(), auth.identityUserId());
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("accepted", result.accepted());
            response.put("active", result.active());
            response.put("queuedBeforeStart", !result.active());
            response.put("code", "A2A_RUNTIME_CANCEL_ACCEPTED");
            return ResponseEntity.accepted().body(response);
        } catch (RuntimeA2aExecutionRegistry.RegistryException forbidden) {
            return ResponseEntity.status(403).body(error(forbidden.code(), forbidden.getMessage()));
        }
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> invalidRequest(IllegalArgumentException invalid) {
        return badRequest("A2A_RUNTIME_REQUEST_INVALID", invalid.getMessage());
    }

    private VerifiedInternalServiceAuth requireA2aAuth(HttpServletRequest request) {
        Object value = request == null ? null
                : request.getAttribute(VerifiedInternalServiceAuth.REQUEST_ATTR);
        if (!(value instanceof VerifiedInternalServiceAuth verified)
                || !IDENTITY_SOURCE_A2A_REMOTE_AGENT.equalsIgnoreCase(verified.identitySource())
                || !StringUtils.hasText(verified.identityUserId())) {
            return null;
        }
        return verified;
    }

    private ResponseEntity<Map<String, Object>> unauthorized() {
        return ResponseEntity.status(401).body(error(
                "RUNTIME_INTERNAL_AUTH_REQUIRED", "internal service authentication failed"));
    }

    private ResponseEntity<Map<String, Object>> badRequest(String code, String message) {
        return ResponseEntity.badRequest().body(error(code, message));
    }

    private Map<String, Object> cancelled(String executionId) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("code", "SUPERVISOR_CANCELLED");
        metadata.put("cancelled", true);
        metadata.put("traceId", executionId);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", false);
        result.put("answer", "Agent execution cancelled");
        result.put("traceId", executionId);
        result.put("metadata", metadata);
        return result;
    }

    private Map<String, Object> error(String code, String message) {
        return Map.of("success", false, "code", code, "answer", message);
    }

    private String identifier(String value, String field) {
        if (!StringUtils.hasText(value) || value.trim().length() > 128) {
            throw new IllegalArgumentException(field + " is required and must be at most 128 characters");
        }
        return value.trim();
    }

    private List<String> safeScopes(List<String> values) {
        if (values == null) {
            return List.of();
        }
        return values.stream()
                .filter(StringUtils::hasText)
                .map(String::trim)
                .filter(value -> value.length() <= 160)
                .distinct()
                .limit(100)
                .toList();
    }

    private void putSafe(Map<String, Object> target, String key, String value) {
        if (StringUtils.hasText(value)) {
            target.put(key, value.trim().substring(0, Math.min(value.trim().length(), 64))
                    .toUpperCase(Locale.ROOT));
        }
    }

    public record A2aRuntimeExecuteRequest(
            String executionId,
            String taskId,
            String contextId,
            String agentId,
            Long agentConfigVersionId,
            String interactionId,
            String message,
            String principalType,
            String trustLevel,
            List<String> scopes) {
        public A2aRuntimeExecuteRequest {
            scopes = scopes == null ? List.of() : List.copyOf(scopes);
        }
    }
}
