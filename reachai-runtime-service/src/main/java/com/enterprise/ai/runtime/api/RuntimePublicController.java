package com.enterprise.ai.runtime.api;

import com.enterprise.ai.runtime.route.RuntimeRouteEvaluationService;
import com.enterprise.ai.runtime.route.RuntimeRouteEvaluationView;
import com.enterprise.ai.runtime.execution.RuntimeAgentExecutionCancellation;
import com.enterprise.ai.runtime.execution.RuntimeAgentExecutionService;
import com.enterprise.ai.runtime.runops.RuntimeRunOpsQueryService;
import com.enterprise.ai.runtime.runops.RuntimeRunOpsReplayService;
import com.enterprise.ai.runtime.runops.RuntimeRunOpsViews.RuntimeRunOpsComparisonView;
import com.enterprise.ai.runtime.runops.RuntimeRunOpsViews.RuntimeRunOpsDiagnosticsView;
import com.enterprise.ai.runtime.runops.RuntimeRunOpsViews.RuntimeRunOpsDetailView;
import com.enterprise.ai.runtime.runops.RuntimeRunOpsViews.RuntimeRunOpsSummaryView;
import com.enterprise.ai.runtime.trace.RuntimeTraceDetailView;
import com.enterprise.ai.runtime.trace.RuntimeTraceQueryService;
import com.enterprise.ai.runtime.trace.RuntimeTraceSummaryView;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledFuture;

/**
 * Runtime-owned public API surface for Agent execution, Trace and RunOps.
 * Control exposes the same canonical routes to management and external callers.
 */
@RestController
@Slf4j
public class RuntimePublicController {

    private static final long AGENT_STREAM_TIMEOUT_MS = 600_000L;
    static final long DEFAULT_HEARTBEAT_INTERVAL_MS = 8_000L;

    private final RuntimeTraceQueryService traceQueryService;
    private final RuntimeAgentExecutionService agentExecutionService;
    private final RuntimeRouteEvaluationService routeEvaluationService;
    private final RuntimeRunOpsQueryService runOpsQueryService;
    private final RuntimeRunOpsReplayService runOpsReplayService;
    private final SseHeartbeatSupport heartbeatSupport;
    private final long agentStreamHeartbeatIntervalMs;

    @Autowired
    public RuntimePublicController(RuntimeTraceQueryService traceQueryService,
                                   RuntimeAgentExecutionService agentExecutionService,
                                   RuntimeRouteEvaluationService routeEvaluationService,
                                   RuntimeRunOpsQueryService runOpsQueryService,
                                   RuntimeRunOpsReplayService runOpsReplayService,
                                   SseHeartbeatSupport heartbeatSupport,
                                   @Value("${reachai.runtime.agent-stream.heartbeat-interval-ms:8000}")
                                   long agentStreamHeartbeatIntervalMs) {
        this.traceQueryService = traceQueryService;
        this.agentExecutionService = agentExecutionService;
        this.routeEvaluationService = routeEvaluationService;
        this.runOpsQueryService = runOpsQueryService;
        this.runOpsReplayService = runOpsReplayService;
        this.heartbeatSupport = heartbeatSupport == null ? new SseHeartbeatSupport() : heartbeatSupport;
        this.agentStreamHeartbeatIntervalMs = agentStreamHeartbeatIntervalMs > 0
                ? agentStreamHeartbeatIntervalMs
                : DEFAULT_HEARTBEAT_INTERVAL_MS;
    }

    /** 测试用：默认 heartbeat 周期与调度器。 */
    RuntimePublicController(RuntimeTraceQueryService traceQueryService,
                            RuntimeAgentExecutionService agentExecutionService,
                            RuntimeRouteEvaluationService routeEvaluationService,
                            RuntimeRunOpsQueryService runOpsQueryService,
                            RuntimeRunOpsReplayService runOpsReplayService) {
        this(traceQueryService, agentExecutionService, routeEvaluationService,
                runOpsQueryService, runOpsReplayService, new SseHeartbeatSupport(), DEFAULT_HEARTBEAT_INTERVAL_MS);
    }

    @PostMapping("/api/runtime/agents/execute")
    public ResponseEntity<Map<String, Object>> executeAgent(@RequestBody(required = false) Map<String, Object> body) {
        return ResponseEntity.ok(agentExecutionService.execute(body, false));
    }

    @PostMapping("/api/runtime/agents/execute/detailed")
    public ResponseEntity<Map<String, Object>> executeAgentDetailed(
            @RequestBody(required = false) Map<String, Object> body) {
        return ResponseEntity.ok(agentExecutionService.execute(body, true));
    }

    @PostMapping(value = "/api/runtime/agents/execute/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<SseEmitter> executeAgentStream(@RequestBody(required = false) Map<String, Object> body) {
        SseEmitter emitter = new SseEmitter(AGENT_STREAM_TIMEOUT_MS);
        Map<String, Object> request = body == null ? new LinkedHashMap<>() : new LinkedHashMap<>(body);
        RuntimeAgentExecutionCancellation cancellation = new RuntimeAgentExecutionCancellation();
        // 先注册生命周期回调 + heartbeat，再启动异步执行，避免竞态
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
            log.debug("[AgentStream] client stream closed: {}", error.getMessage());
        });
        CompletableFuture.runAsync(() -> {
            try {
                streamAgentExecution(emitter, request, cancellation);
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

    @DeleteMapping("/api/runtime/agents/sessions/{sessionId}")
    public ResponseEntity<Void> clearAgentSession(@PathVariable String sessionId) {
        agentExecutionService.clearSession(sessionId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/api/runtime/agents/route-evaluation")
    public ResponseEntity<RuntimeRouteEvaluationView> routeEvaluation(
            @RequestParam(defaultValue = "30") int days) {
        return ResponseEntity.ok(routeEvaluationService.evaluate(days));
    }

    @GetMapping("/api/traces/{traceId}")
    public ResponseEntity<RuntimeTraceDetailView> getTrace(@PathVariable String traceId) {
        Optional<RuntimeTraceDetailView> detail = traceQueryService.getTraceDetail(traceId);
        return detail.map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/api/traces/recent")
    public ResponseEntity<List<RuntimeTraceSummaryView>> listRecentTraces(
            @RequestParam(required = false) String userId,
            @RequestParam(defaultValue = "30") int days,
            @RequestParam(defaultValue = "20") int limit) {
        return ResponseEntity.ok(traceQueryService.listRecentTraces(userId, limit, days));
    }

    @GetMapping("/api/runops/traces/{traceId}")
    public ResponseEntity<?> runOpsDetail(@PathVariable String traceId) {
        try {
            RuntimeRunOpsDetailView detail = runOpsQueryService.detail(traceId);
            return ResponseEntity.ok(detail);
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(new ApiErrorResponse(ex.getMessage()));
        }
    }

    @GetMapping("/api/runops/traces/recent")
    public ResponseEntity<List<RuntimeRunOpsSummaryView>> runOpsRecent(
            @RequestParam(required = false) String projectCode,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String runType,
            @RequestParam(required = false) String entryType,
            @RequestParam(required = false) String agentId,
            @RequestParam(required = false) String userId,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(defaultValue = "7") int days) {
        return ResponseEntity.ok(runOpsQueryService.recent(
                projectCode, status, runType, entryType, agentId, userId, keyword, limit, days));
    }

    @GetMapping("/api/runops/diagnostics")
    public ResponseEntity<RuntimeRunOpsDiagnosticsView> runOpsDiagnostics(
            @RequestParam(required = false) String projectCode,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String runType,
            @RequestParam(required = false) String entryType,
            @RequestParam(required = false) String agentId,
            @RequestParam(required = false) String userId,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(defaultValue = "7") int days) {
        return ResponseEntity.ok(runOpsQueryService.diagnostics(
                projectCode, status, runType, entryType, agentId, userId, keyword, limit, days));
    }

    @GetMapping("/api/runops/traces/{traceId}/compare/{candidateTraceId}")
    public ResponseEntity<?> runOpsCompare(@PathVariable String traceId,
                                           @PathVariable String candidateTraceId) {
        try {
            RuntimeRunOpsComparisonView comparison = runOpsQueryService.compare(traceId, candidateTraceId);
            return ResponseEntity.ok(comparison);
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(new ApiErrorResponse(ex.getMessage()));
        }
    }

    @PostMapping("/api/runops/traces/{traceId}/replay")
    public ResponseEntity<?> runOpsReplay(@PathVariable String traceId,
                                          @RequestBody(required = false) RuntimeRunOpsReplayService.ReplayRequest request) {
        try {
            return ResponseEntity.ok(runOpsReplayService.replay(traceId, request));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(new ApiErrorResponse(ex.getMessage()));
        }
    }

    public record ApiErrorResponse(String message) {
    }

    private void streamAgentExecution(SseEmitter emitter,
                                      Map<String, Object> request,
                                      RuntimeAgentExecutionCancellation cancellation) {
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
                    cancellation);
            if (cancellation.isCancelled()) {
                return;
            }
            if (isExecutionFailure(result)) {
                // success=false：禁止把错误文案当 message.delta，禁止 execution.completed
                // 客户端已断开 / Supervisor 取消：不再强行发送 error
                if (!cancellation.isCancelled() && !isSupervisorCancelled(result)) {
                    sendEvent(emitter, "execution.error", toExecutionErrorPayload(result, request), cancellation);
                }
                if (!cancellation.isCancelled()) {
                    emitter.complete();
                }
                return;
            }
            Object answer = result.get("answer");
            boolean contentAlreadyStreamed = isContentStreamed(result);
            if (!contentAlreadyStreamed && answer != null && !String.valueOf(answer).isEmpty()) {
                sendEvent(emitter, "message.delta", Map.of("text", String.valueOf(answer)), cancellation);
            }
            if (result.get("uiRequest") != null) {
                sendEvent(emitter, "ui.requested", result.get("uiRequest"), cancellation);
            }
            sendEvent(emitter, "execution.completed", result, cancellation);
            if (!cancellation.isCancelled()) {
                emitter.complete();
            }
        } catch (Exception ex) {
            if (cancellation.isCancelled()) {
                return;
            }
            log.warn("[AgentStream] execution failed", ex);
            try {
                sendEvent(emitter, "execution.error", Map.of(
                        "code", "AGENT_STREAM_FAILED",
                        "message", rootMessage(ex)), cancellation);
                if (!cancellation.isCancelled()) {
                    emitter.complete();
                }
            } catch (Exception sendError) {
                cancellation.cancel();
                emitter.completeWithError(ex);
            }
        }
    }

    static boolean isExecutionFailure(Map<String, Object> result) {
        if (result == null) {
            return true;
        }
        Object success = result.get("success");
        return Boolean.FALSE.equals(success) || "false".equalsIgnoreCase(String.valueOf(success));
    }

    static boolean isSupervisorCancelled(Map<String, Object> result) {
        if (result == null) {
            return false;
        }
        if ("SUPERVISOR_CANCELLED".equals(String.valueOf(result.get("code")))) {
            return true;
        }
        Object metadata = result.get("metadata");
        if (metadata instanceof Map<?, ?> map) {
            return "SUPERVISOR_CANCELLED".equals(String.valueOf(map.get("code")));
        }
        return false;
    }

    /**
     * 将 Agent 执行失败结果收口为 execution.error 载荷（不含 reasoning/prompt/凭证）。
     */
    @SuppressWarnings("unchecked")
    static Map<String, Object> toExecutionErrorPayload(Map<String, Object> result, Map<String, Object> request) {
        Map<String, Object> payload = new LinkedHashMap<>();
        Map<String, Object> metadata = result.get("metadata") instanceof Map<?, ?> raw
                ? new LinkedHashMap<>((Map<String, Object>) raw)
                : new LinkedHashMap<>();
        String code = firstNonBlank(
                text(metadata.get("code")),
                text(result.get("code")),
                "AGENT_EXECUTION_FAILED");
        String message = firstNonBlank(
                text(result.get("answer")),
                text(result.get("message")),
                text(metadata.get("message")),
                "Agent 执行失败");
        String traceId = firstNonBlank(text(metadata.get("traceId")), text(result.get("traceId")));
        String sessionId = firstNonBlank(
                text(result.get("sessionId")),
                text(metadata.get("sessionId")),
                request == null ? null : text(request.get("sessionId")));

        payload.put("code", code);
        payload.put("message", message);
        putIfPresentStatic(payload, "traceId", traceId);
        putIfPresentStatic(payload, "sessionId", sessionId);

        Map<String, Object> modelMeta = metadata.get("model") instanceof Map<?, ?> rawModel
                ? new LinkedHashMap<>((Map<String, Object>) rawModel)
                : new LinkedHashMap<>();
        Map<String, Object> safeMeta = sanitizeErrorMetadata(metadata);
        Object finishReason = firstNonNull(metadata.get("finishReason"), modelMeta.get("finishReason"));
        Object modelInstanceId = firstNonNull(metadata.get("modelInstanceId"), modelMeta.get("modelInstanceId"));
        putIfPresentStatic(safeMeta, "finishReason", finishReason);
        putIfPresentStatic(safeMeta, "modelInstanceId", modelInstanceId);
        Object usage = firstNonNull(metadata.get("usage"), modelMeta.get("usage"));
        if (usage instanceof Map<?, ?> usageMap) {
            safeMeta.put("usage", new LinkedHashMap<>((Map<String, Object>) usageMap));
        }
        Object counts = firstNonNull(metadata.get("eventCounts"), modelMeta.get("eventCounts"));
        if (counts instanceof Map<?, ?> countMap) {
            safeMeta.put("eventCounts", new LinkedHashMap<>((Map<String, Object>) countMap));
        }
        // 提升诊断计数到 metadata，便于前端/Trace 直接读取
        for (String key : List.of(
                "contentDeltaCount", "reasoningDeltaCount", "reasoningLength",
                "toolCallDeltaCount", "assembledToolCallCount", "consumedAnyEvent",
                "completedReceived", "emittedPublicContent", "contentLength")) {
            Object value = firstNonNull(metadata.get(key), modelMeta.get(key));
            putIfPresentStatic(safeMeta, key, value);
        }
        if (!safeMeta.isEmpty()) {
            payload.put("metadata", safeMeta);
        }
        return payload;
    }

    private static Object firstNonNull(Object... values) {
        if (values == null) {
            return null;
        }
        for (Object value : values) {
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    static Map<String, Object> sanitizeErrorMetadata(Map<String, Object> source) {
        Map<String, Object> safe = new LinkedHashMap<>();
        if (source == null) {
            return safe;
        }
        for (Map.Entry<String, Object> entry : source.entrySet()) {
            String key = entry.getKey();
            if (key == null) {
                continue;
            }
            String lower = key.toLowerCase();
            if (lower.contains("reasoningcontent")
                    || lower.contains("reasoning_content")
                    || lower.contains("prompt")
                    || lower.contains("apikey")
                    || lower.contains("authorization")
                    || lower.contains("credential")
                    || "exception".equals(lower)
                    || "model".equals(lower)
                    || "answer".equals(lower)) {
                // model 诊断由调用方提升安全字段；不整包拷贝以免夹带 reasoning 正文
                continue;
            }
            Object value = entry.getValue();
            if (value instanceof String text && text.length() > 500) {
                continue;
            }
            if (value instanceof Map<?, ?> nested) {
                @SuppressWarnings("unchecked")
                Map<String, Object> nestedSafe = sanitizeErrorMetadata(new LinkedHashMap<>((Map<String, Object>) nested));
                if (!nestedSafe.isEmpty()) {
                    safe.put(key, nestedSafe);
                }
                continue;
            }
            safe.put(key, value);
        }
        return safe;
    }

    private static void putIfPresentStatic(Map<String, Object> target, String key, Object value) {
        if (value != null) {
            target.put(key, value);
        }
    }

    private static String text(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value);
        return text.isBlank() || "null".equals(text) ? null : text;
    }

    private static String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
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
            throw new IllegalStateException("Agent stream client disconnected", ex);
        }
    }

    private void putIfPresent(Map<String, Object> target, String key, Object value) {
        if (value != null) target.put(key, value);
    }

    @SuppressWarnings("unchecked")
    private boolean isContentStreamed(Map<String, Object> result) {
        Object metadata = result.get("metadata");
        if (!(metadata instanceof Map<?, ?> meta)) {
            return false;
        }
        Object model = meta.get("model");
        if (model instanceof Map<?, ?> modelMeta) {
            Object flagged = modelMeta.get("contentStreamed");
            if (Boolean.TRUE.equals(flagged) || "true".equalsIgnoreCase(String.valueOf(flagged))) {
                return true;
            }
        }
        Object flagged = meta.get("contentStreamed");
        return Boolean.TRUE.equals(flagged) || "true".equalsIgnoreCase(String.valueOf(flagged));
    }

    private String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null) current = current.getCause();
        String message = current.getMessage();
        return message == null || message.isBlank() ? current.getClass().getSimpleName() : message;
    }

}
