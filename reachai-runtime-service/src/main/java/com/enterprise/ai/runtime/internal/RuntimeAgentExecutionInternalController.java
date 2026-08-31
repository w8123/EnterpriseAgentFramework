package com.enterprise.ai.runtime.internal;

import com.enterprise.ai.runtime.api.SseHeartbeatSupport;
import com.enterprise.ai.runtime.execution.RuntimeAgentExecutionCancellation;
import com.enterprise.ai.runtime.execution.RuntimeAgentExecutionService;
import com.enterprise.ai.runtime.execution.TrustedControlTiming;
import com.enterprise.ai.runtime.execution.TrustedPersonalMemoryContext;
import com.enterprise.ai.runtime.execution.identity.WorkflowExecutionIdentity;
import com.enterprise.ai.runtime.internalauth.VerifiedInternalServiceAuth;
import com.enterprise.ai.runtime.memory.RuntimeSessionRetentionException;
import com.enterprise.ai.runtime.execution.SupervisorRuntimeAdapter;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledFuture;

import static com.enterprise.ai.common.internalauth.InternalServiceAuthHeaders.IDENTITY_SOURCE_A2A_REMOTE_AGENT;

/**
 * HMAC-authenticated Control→Runtime Agent execute entry (sync + SSE).
 * Trusted identity is taken only from verified internal auth headers, never from public body fields.
 */
@RestController
@Slf4j
public class RuntimeAgentExecutionInternalController {

    private static final long AGENT_STREAM_TIMEOUT_MS = 600_000L;
    private static final long DEFAULT_HEARTBEAT_INTERVAL_MS = 8_000L;

    private final RuntimeAgentExecutionService agentExecutionService;
    private final SseHeartbeatSupport heartbeatSupport;
    private final long agentStreamHeartbeatIntervalMs;

    public RuntimeAgentExecutionInternalController(
            RuntimeAgentExecutionService agentExecutionService,
            SseHeartbeatSupport heartbeatSupport,
            @Value("${reachai.runtime.agent-stream.heartbeat-interval-ms:8000}")
            long agentStreamHeartbeatIntervalMs) {
        this.agentExecutionService = agentExecutionService;
        this.heartbeatSupport = heartbeatSupport == null ? new SseHeartbeatSupport() : heartbeatSupport;
        this.agentStreamHeartbeatIntervalMs = agentStreamHeartbeatIntervalMs > 0
                ? agentStreamHeartbeatIntervalMs
                : DEFAULT_HEARTBEAT_INTERVAL_MS;
    }

    @PostMapping("/internal/runtime/agents/execute")
    public ResponseEntity<Map<String, Object>> execute(HttpServletRequest httpRequest,
                                                       @RequestBody TrustedAgentExecuteRequest request) {
        WorkflowExecutionIdentity identity = requireVerifiedIdentity(httpRequest, request);
        if (identity == null) {
            return ResponseEntity.status(401).body(Map.of(
                    "success", false,
                    "code", "RUNTIME_INTERNAL_AUTH_REQUIRED",
                    "answer", "internal service authentication failed"));
        }
        if (request == null || request.body() == null) {
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "answer", "body is required",
                    "metadata", Map.of("code", "RUNTIME_AGENT_BODY_REQUIRED")));
        }
        Map<String, Object> rawBody = new LinkedHashMap<>(request.body());
        // Extract only after HMAC/nonce/identity verification above succeeded.
        TrustedControlTiming controlTiming = TrustedControlTiming.extractAndRemoveFromBody(rawBody);
        Map<String, Object> body = sanitizePublicBody(rawBody);
        Map<String, Object> result = agentExecutionService.execute(
                body,
                false,
                SupervisorRuntimeAdapter.SupervisorEventSink.NOOP,
                RuntimeAgentExecutionCancellation.NOOP,
                identity,
                controlTiming,
                request.personalMemory());
        return responseForAgentResult(result);
    }

    @DeleteMapping("/internal/runtime/agents/sessions/{sessionId}")
    public ResponseEntity<Void> clearSession(HttpServletRequest httpRequest,
                                             @PathVariable String sessionId) {
        WorkflowExecutionIdentity identity = requireVerifiedIdentity(httpRequest, null);
        if (identity == null) {
            return ResponseEntity.status(401).build();
        }
        try {
            agentExecutionService.clearSession(sessionId, identity);
            return ResponseEntity.noContent().build();
        } catch (RuntimeSessionRetentionException lifecycle) {
            return ResponseEntity.status(lifecycle.status()).build();
        }
    }

    @PostMapping(value = "/internal/runtime/agents/execute/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<SseEmitter> executeStream(HttpServletRequest httpRequest,
                                                    @RequestBody TrustedAgentExecuteRequest request) {
        WorkflowExecutionIdentity identity = requireVerifiedIdentity(httpRequest, request);
        if (identity == null) {
            SseEmitter rejected = new SseEmitter(0L);
            try {
                rejected.send(SseEmitter.event()
                        .name("execution.error")
                        .data(Map.of(
                                "success", false,
                                "code", "RUNTIME_INTERNAL_AUTH_REQUIRED",
                                "message", "internal service authentication failed")));
                rejected.complete();
            } catch (IOException ignored) {
                rejected.completeWithError(ignored);
            }
            return ResponseEntity.status(401)
                    .contentType(MediaType.TEXT_EVENT_STREAM)
                    .body(rejected);
        }
        Map<String, Object> rawBody = new LinkedHashMap<>(request == null || request.body() == null
                ? Map.of()
                : request.body());
        // Extract only after HMAC/nonce/identity verification above succeeded.
        TrustedControlTiming controlTiming = TrustedControlTiming.extractAndRemoveFromBody(rawBody);
        Map<String, Object> body = sanitizePublicBody(rawBody);
        SseEmitter emitter = new SseEmitter(AGENT_STREAM_TIMEOUT_MS);
        RuntimeAgentExecutionCancellation cancellation = new RuntimeAgentExecutionCancellation();
        ScheduledFuture<?> heartbeat = heartbeatSupport.start(
                emitter, agentStreamHeartbeatIntervalMs, cancellation::cancel);
        Runnable stopHeartbeatAndCancel = () -> {
            heartbeatSupport.stop(heartbeat);
            cancellation.cancel();
        };
        emitter.onCompletion(stopHeartbeatAndCancel);
        emitter.onTimeout(() -> {
            stopHeartbeatAndCancel.run();
            emitter.complete();
        });
        emitter.onError(error -> {
            stopHeartbeatAndCancel.run();
            log.debug("[InternalAgentStream] client stream closed: {}", error.getMessage());
        });
        WorkflowExecutionIdentity trustedIdentity = identity;
        TrustedControlTiming trustedTiming = controlTiming;
        CompletableFuture.runAsync(() -> {
            try {
                streamAgentExecution(emitter, body, cancellation, trustedIdentity, trustedTiming,
                        request == null ? TrustedPersonalMemoryContext.empty() : request.personalMemory());
            } finally {
                heartbeatSupport.stop(heartbeat);
            }
        });
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noCache())
                .header("X-Accel-Buffering", "no")
                .header("Cache-Control", "no-cache, no-transform")
                .contentType(MediaType.TEXT_EVENT_STREAM)
                .body(emitter);
    }

    private void streamAgentExecution(SseEmitter emitter,
                                      Map<String, Object> request,
                                      RuntimeAgentExecutionCancellation cancellation,
                                      WorkflowExecutionIdentity identity,
                                      TrustedControlTiming controlTiming,
                                      TrustedPersonalMemoryContext personalMemory) {
        try {
            Map<String, Object> started = new LinkedHashMap<>();
            putIfPresent(started, "agentId", request.get("agentId"));
            putIfPresent(started, "sessionId", request.get("sessionId"));
            sendEvent(emitter, "execution.started", started, cancellation);
            if (cancellation.isCancelled()) {
                return;
            }
            Map<String, Object> result = agentExecutionService.execute(
                    request,
                    true,
                    (event, data) -> sendEvent(emitter, event, data, cancellation),
                    cancellation,
                    identity,
                    controlTiming,
                    personalMemory);
            if (cancellation.isCancelled()) {
                return;
            }
            if (isExecutionFailure(result)) {
                if (!cancellation.isCancelled() && !isSupervisorCancelled(result)) {
                    sendEvent(emitter, "execution.error", toExecutionErrorPayload(result, request), cancellation);
                }
                if (!cancellation.isCancelled()) {
                    emitter.complete();
                }
                return;
            }
            Object answer = result.get("answer");
            boolean waiting = isInteractionWaiting(result);
            boolean contentAlreadyStreamed = isContentStreamed(result);
            if (!waiting && !contentAlreadyStreamed && answer != null && !String.valueOf(answer).isEmpty()) {
                sendEvent(emitter, "message.delta", Map.of("text", String.valueOf(answer)), cancellation);
            }
            if (result.get("uiRequest") != null) {
                sendEvent(emitter, "ui.requested", result.get("uiRequest"), cancellation);
            }
            if (waiting) {
                Map<String, Object> waitingPayload = new LinkedHashMap<>();
                waitingPayload.put("status", "WAITING_USER");
                Object metadata = result.get("metadata");
                if (metadata instanceof Map<?, ?> map && map.get("interactionId") != null) {
                    waitingPayload.put("interactionId", map.get("interactionId"));
                }
                if (result.get("uiRequest") != null) {
                    waitingPayload.put("uiRequest", result.get("uiRequest"));
                }
                sendEvent(emitter, "turn.waiting", waitingPayload, cancellation);
            }
            sendEvent(emitter, "execution.completed", result, cancellation);
            if (!cancellation.isCancelled()) {
                emitter.complete();
            }
        } catch (Exception ex) {
            if (cancellation.isCancelled()) {
                return;
            }
            log.warn("[InternalAgentStream] execution failed", ex);
            try {
                sendEvent(emitter, "execution.error", Map.of(
                        "code", "AGENT_STREAM_FAILED",
                        "message", ex.getMessage() == null ? "stream failed" : ex.getMessage()), cancellation);
                if (!cancellation.isCancelled()) {
                    emitter.complete();
                }
            } catch (Exception sendError) {
                cancellation.cancel();
                emitter.completeWithError(ex);
            }
        }
    }

    /**
     * Identity is constructed only from cryptographically verified auth attributes.
     * Body identity payload must match signed headers when present; otherwise rejected.
     */
    private static WorkflowExecutionIdentity requireVerifiedIdentity(HttpServletRequest httpRequest,
                                                                    TrustedAgentExecuteRequest request) {
        Object attr = httpRequest == null ? null : httpRequest.getAttribute(VerifiedInternalServiceAuth.REQUEST_ATTR);
        if (!(attr instanceof VerifiedInternalServiceAuth verified)) {
            return null;
        }
        if (request != null && request.identity() != null) {
            TrustedIdentityPayload payload = request.identity();
            String bodySource = payload.source() == null ? "" : payload.source().trim().toUpperCase(Locale.ROOT);
            String bodyTenantId = payload.tenantId() == null ? "" : payload.tenantId().trim();
            String bodyUserId = payload.userId() == null ? "" : payload.userId().trim();
            String authTenantId = verified.identityTenantId() == null ? "" : verified.identityTenantId();
            String authUserId = verified.identityUserId() == null ? "" : verified.identityUserId();
            if (StringUtils.hasText(bodySource) && !bodySource.equals(verified.identitySource())) {
                return null;
            }
            if (StringUtils.hasText(bodyUserId) && !bodyUserId.equals(authUserId)) {
                return null;
            }
            if (StringUtils.hasText(bodyTenantId) && !bodyTenantId.equals(authTenantId)) {
                return null;
            }
        }
        return resolveIdentity(
                verified.identitySource(), verified.identityTenantId(), verified.identityUserId());
    }

    private static WorkflowExecutionIdentity resolveIdentity(String source, String tenantId, String userId) {
        if (!StringUtils.hasText(source)) {
            return null;
        }
        String normalized = source.trim().toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "EMBED_SESSION" -> StringUtils.hasText(userId)
                    ? WorkflowExecutionIdentity.fromEmbedSession(tenantId, null, null, userId)
                    : null;
            case "AGENT" -> StringUtils.hasText(userId)
                    ? WorkflowExecutionIdentity.fromAgent(tenantId, null, null, userId)
                    : WorkflowExecutionIdentity.fromAgent(tenantId, null, null, null);
            case IDENTITY_SOURCE_A2A_REMOTE_AGENT -> StringUtils.hasText(userId)
                    ? WorkflowExecutionIdentity.fromA2aRemoteAgent(tenantId, null, null, userId)
                    : null;
            default -> null;
        };
    }

    private static Map<String, Object> sanitizePublicBody(Map<String, Object> body) {
        Map<String, Object> safe = new LinkedHashMap<>(body);
        safe.remove("__workflowExecutionIdentity");
        safe.remove("trustedIdentity");
        safe.remove("_trustedUserId");
        safe.remove("__memoryTurnId");
        safe.remove("__memoryUserMessage");
        safe.remove("personalMemory");
        safe.remove("personalMemoryContext");
        safe.remove("__personalMemory");
        // Timing is extracted separately after auth; never leave forgeable keys on the body.
        safe.remove("controlTiming");
        safe.keySet().removeIf(key -> key != null && key.startsWith("control."));
        if (safe.get("metadata") instanceof Map<?, ?> rawMeta) {
            Map<String, Object> metadata = new LinkedHashMap<>();
            rawMeta.forEach((k, v) -> {
                String key = k == null ? null : String.valueOf(k);
                if (key != null && !key.startsWith("control.") && !"controlTiming".equals(key)) {
                    metadata.put(key, v);
                }
            });
            if (metadata.isEmpty()) {
                safe.remove("metadata");
            } else {
                safe.put("metadata", metadata);
            }
        }
        return safe;
    }

    private static ResponseEntity<Map<String, Object>> responseForAgentResult(Map<String, Object> result) {
        if (result == null) {
            return ResponseEntity.ok(Map.of("success", false));
        }
        Object code = result.get("metadata") instanceof Map<?, ?> meta ? meta.get("code") : result.get("code");
        String codeText = code == null ? "" : String.valueOf(code);
        if ("RUNTIME_INTERACTION_FORBIDDEN".equals(codeText)) {
            return ResponseEntity.status(403).body(result);
        }
        if ("RUNTIME_INTERACTION_CONFLICT".equals(codeText)
                || "RUNTIME_SESSION_OWNERSHIP_CONFLICT".equals(codeText)) {
            return ResponseEntity.status(409).body(result);
        }
        if ("RUNTIME_INTERACTION_EXPIRED".equals(codeText)) {
            return ResponseEntity.status(410).body(result);
        }
        return ResponseEntity.ok(result);
    }

    private void sendEvent(SseEmitter emitter,
                           String event,
                           Object data,
                           RuntimeAgentExecutionCancellation cancellation) {
        if (cancellation != null && cancellation.isCancelled()) {
            return;
        }
        try {
            synchronized (emitter) {
                emitter.send(SseEmitter.event().name(event).data(data));
            }
        } catch (IOException ex) {
            if (cancellation != null) {
                cancellation.cancel();
            }
        }
    }

    private static void putIfPresent(Map<String, Object> target, String key, Object value) {
        if (value != null) {
            target.put(key, value);
        }
    }

    private static boolean isExecutionFailure(Map<String, Object> result) {
        if (result == null) {
            return true;
        }
        // Waiting for a blocking interaction is a suspended turn, not an execution failure.
        // Supervisor approval deliberately returns success=false until the user confirms.
        if (isInteractionWaiting(result)) {
            return false;
        }
        Object success = result.get("success");
        return Boolean.FALSE.equals(success) || "false".equalsIgnoreCase(String.valueOf(success));
    }

    private static boolean isSupervisorCancelled(Map<String, Object> result) {
        Object code = result.get("metadata") instanceof Map<?, ?> meta ? meta.get("code") : result.get("code");
        return "RUNTIME_SUPERVISOR_CANCELLED".equals(String.valueOf(code));
    }

    private static boolean isInteractionWaiting(Map<String, Object> result) {
        if (result == null) {
            return false;
        }
        Object waiting = result.get("waiting");
        if (waiting instanceof Boolean b) {
            return b;
        }
        if (!(result.get("metadata") instanceof Map<?, ?> meta)) {
            return false;
        }
        if (Boolean.TRUE.equals(meta.get("waiting")) || Boolean.TRUE.equals(meta.get("interactionPending"))) {
            return true;
        }
        String code = String.valueOf(meta.get("code"));
        return "RUNTIME_GRAPH_INTERACTION_WAITING".equals(code)
                || "SUPERVISOR_CONFIRMATION_REQUIRED".equals(code);
    }

    private static boolean isContentStreamed(Map<String, Object> result) {
        return result.get("metadata") instanceof Map<?, ?> meta && Boolean.TRUE.equals(meta.get("contentStreamed"));
    }

    private static Map<String, Object> toExecutionErrorPayload(Map<String, Object> result, Map<String, Object> request) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("success", false);
        Object code = result.get("metadata") instanceof Map<?, ?> meta ? meta.get("code") : result.get("code");
        putIfPresent(payload, "code", code);
        putIfPresent(payload, "answer", result.get("answer"));
        putIfPresent(payload, "message", result.get("answer"));
        putIfPresent(payload, "agentId", request.get("agentId"));
        putIfPresent(payload, "sessionId", request.get("sessionId"));
        return payload;
    }

    public record TrustedAgentExecuteRequest(
            Map<String, Object> body,
            TrustedIdentityPayload identity,
            TrustedPersonalMemoryContext personalMemory
    ) {
        public TrustedAgentExecuteRequest {
            personalMemory = personalMemory == null ? TrustedPersonalMemoryContext.empty() : personalMemory;
        }
    }

    /**
     * Identity envelope echoed by Control for cross-check against signed headers.
     * Trust flags are never accepted from JSON.
     */
    public record TrustedIdentityPayload(
            String source,
            String tenantId,
            String userId
    ) {
    }
}
