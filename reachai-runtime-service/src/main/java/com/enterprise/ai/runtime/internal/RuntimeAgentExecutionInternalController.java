package com.enterprise.ai.runtime.internal;

import com.enterprise.ai.runtime.api.SseHeartbeatSupport;
import com.enterprise.ai.runtime.execution.RuntimeAgentExecutionCancellation;
import com.enterprise.ai.runtime.execution.RuntimeAgentExecutionService;
import com.enterprise.ai.runtime.execution.identity.WorkflowExecutionIdentity;
import com.enterprise.ai.runtime.internalauth.VerifiedInternalServiceAuth;
import com.enterprise.ai.runtime.supervisor.SupervisorRuntimeAdapter;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledFuture;

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
        Map<String, Object> body = sanitizePublicBody(request.body());
        Map<String, Object> result = agentExecutionService.execute(
                body,
                false,
                SupervisorRuntimeAdapter.SupervisorEventSink.NOOP,
                RuntimeAgentExecutionCancellation.NOOP,
                identity);
        return responseForAgentResult(result);
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
        Map<String, Object> body = sanitizePublicBody(request == null || request.body() == null
                ? Map.of()
                : request.body());
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
        CompletableFuture.runAsync(() -> {
            try {
                streamAgentExecution(emitter, body, cancellation, trustedIdentity);
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
                                      WorkflowExecutionIdentity identity) {
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
                    identity);
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
            String bodyUserId = payload.userId() == null ? "" : payload.userId().trim();
            String authUserId = verified.identityUserId() == null ? "" : verified.identityUserId();
            if (StringUtils.hasText(bodySource) && !bodySource.equals(verified.identitySource())) {
                return null;
            }
            if (StringUtils.hasText(bodyUserId) && !bodyUserId.equals(authUserId)) {
                return null;
            }
        }
        return resolveIdentity(verified.identitySource(), verified.identityUserId());
    }

    private static WorkflowExecutionIdentity resolveIdentity(String source, String userId) {
        if (!StringUtils.hasText(source)) {
            return null;
        }
        String normalized = source.trim().toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "EMBED_SESSION" -> StringUtils.hasText(userId)
                    ? WorkflowExecutionIdentity.fromEmbedSession(null, null, userId)
                    : null;
            case "AGENT" -> StringUtils.hasText(userId)
                    ? WorkflowExecutionIdentity.fromAgent(null, null, userId)
                    : WorkflowExecutionIdentity.fromAgent(null, null);
            default -> null;
        };
    }

    private static Map<String, Object> sanitizePublicBody(Map<String, Object> body) {
        Map<String, Object> safe = new LinkedHashMap<>(body);
        safe.remove("__workflowExecutionIdentity");
        safe.remove("trustedIdentity");
        safe.remove("_trustedUserId");
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
        if ("RUNTIME_INTERACTION_CONFLICT".equals(codeText)) {
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
        Object success = result.get("success");
        return success instanceof Boolean b && !b;
    }

    private static boolean isSupervisorCancelled(Map<String, Object> result) {
        Object code = result.get("metadata") instanceof Map<?, ?> meta ? meta.get("code") : result.get("code");
        return "RUNTIME_SUPERVISOR_CANCELLED".equals(String.valueOf(code));
    }

    private static boolean isInteractionWaiting(Map<String, Object> result) {
        Object waiting = result.get("waiting");
        if (waiting instanceof Boolean b) {
            return b;
        }
        return result.get("metadata") instanceof Map<?, ?> meta && Boolean.TRUE.equals(meta.get("waiting"));
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
            TrustedIdentityPayload identity
    ) {
    }

    /**
     * Identity envelope echoed by Control for cross-check against signed headers.
     * Trust flags are never accepted from JSON.
     */
    public record TrustedIdentityPayload(
            String source,
            String userId
    ) {
    }
}
