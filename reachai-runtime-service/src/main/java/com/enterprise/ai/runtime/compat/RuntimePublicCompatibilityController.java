package com.enterprise.ai.runtime.compat;

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
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Runtime public route compatibility surface for paths that frontend callers still reach through /api.
 * Each route delegates to Runtime-owned implementations; Control keeps the public /api shape stable.
 */
@RestController
@RequiredArgsConstructor
public class RuntimePublicCompatibilityController {

    private final RuntimeTraceQueryService traceQueryService;
    private final RuntimeAgentExecutionService agentExecutionService;
    private final RuntimeRouteEvaluationService routeEvaluationService;
    private final RuntimeRunOpsQueryService runOpsQueryService;
    private final RuntimeRunOpsReplayService runOpsReplayService;

    @PostMapping({"/api/agent/execute", "/api/runtime/agents/execute"})
    public ResponseEntity<Map<String, Object>> executeAgent(@RequestBody(required = false) Map<String, Object> body) {
        return ResponseEntity.ok(agentExecutionService.execute(body, false));
    }

    @PostMapping({"/api/agent/execute/detailed", "/api/runtime/agents/execute/detailed"})
    public ResponseEntity<Map<String, Object>> executeAgentDetailed(
            @RequestBody(required = false) Map<String, Object> body) {
        return ResponseEntity.ok(agentExecutionService.execute(body, true));
    }

    @GetMapping({"/api/agent/route-evaluation", "/api/runtime/agents/route-evaluation"})
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
            @RequestParam(required = false) String userId,
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(defaultValue = "7") int days) {
        return ResponseEntity.ok(runOpsQueryService.recent(userId, limit, days));
    }

    @GetMapping("/api/runops/diagnostics")
    public ResponseEntity<RuntimeRunOpsDiagnosticsView> runOpsDiagnostics(
            @RequestParam(required = false) String userId,
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(defaultValue = "7") int days) {
        return ResponseEntity.ok(runOpsQueryService.diagnostics(userId, limit, days));
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

}
