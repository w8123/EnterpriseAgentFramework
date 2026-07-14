package com.enterprise.ai.runtime.api;

import com.enterprise.ai.runtime.route.RuntimeRouteEvaluationService;
import com.enterprise.ai.runtime.route.RuntimeRouteEvaluationView;
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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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

/**
 * Runtime-owned public API surface for Agent execution, Trace and RunOps.
 * Control exposes the same canonical routes to management and external callers.
 */
@RestController
@RequiredArgsConstructor
@Slf4j
public class RuntimePublicController {

    private static final long AGENT_STREAM_TIMEOUT_MS = 600_000L;

    private final RuntimeTraceQueryService traceQueryService;
    private final RuntimeAgentExecutionService agentExecutionService;
    private final RuntimeRouteEvaluationService routeEvaluationService;
    private final RuntimeRunOpsQueryService runOpsQueryService;
    private final RuntimeRunOpsReplayService runOpsReplayService;

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
    public SseEmitter executeAgentStream(@RequestBody(required = false) Map<String, Object> body) {
        SseEmitter emitter = new SseEmitter(AGENT_STREAM_TIMEOUT_MS);
        Map<String, Object> request = body == null ? new LinkedHashMap<>() : new LinkedHashMap<>(body);
        CompletableFuture.runAsync(() -> streamAgentExecution(emitter, request));
        emitter.onTimeout(emitter::complete);
        emitter.onError(error -> log.debug("[AgentStream] client stream closed: {}", error.getMessage()));
        return emitter;
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

    private void streamAgentExecution(SseEmitter emitter, Map<String, Object> request) {
        try {
            Map<String, Object> started = new LinkedHashMap<>();
            putIfPresent(started, "agentId", request.get("agentId"));
            putIfPresent(started, "sessionId", request.get("sessionId"));
            sendEvent(emitter, "execution.started", started);

            Map<String, Object> result = agentExecutionService.execute(
                    request,
                    true,
                    (event, data) -> sendEvent(emitter, event, data));
            Object answer = result.get("answer");
            if (answer != null && !String.valueOf(answer).isEmpty()) {
                sendEvent(emitter, "message.delta", Map.of("text", String.valueOf(answer)));
            }
            if (result.get("uiRequest") != null) {
                sendEvent(emitter, "ui.requested", result.get("uiRequest"));
            }
            sendEvent(emitter, "execution.completed", result);
            emitter.complete();
        } catch (Exception ex) {
            log.warn("[AgentStream] execution failed", ex);
            try {
                sendEvent(emitter, "execution.error", Map.of(
                        "code", "AGENT_STREAM_FAILED",
                        "message", rootMessage(ex)));
                emitter.complete();
            } catch (Exception sendError) {
                emitter.completeWithError(ex);
            }
        }
    }

    private void sendEvent(SseEmitter emitter, String event, Object data) {
        try {
            synchronized (emitter) {
                emitter.send(SseEmitter.event().name(event).data(data));
            }
        } catch (IOException ex) {
            throw new IllegalStateException("Agent stream client disconnected", ex);
        }
    }

    private void putIfPresent(Map<String, Object> target, String key, Object value) {
        if (value != null) target.put(key, value);
    }

    private String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null) current = current.getCause();
        String message = current.getMessage();
        return message == null || message.isBlank() ? current.getClass().getSimpleName() : message;
    }

}
