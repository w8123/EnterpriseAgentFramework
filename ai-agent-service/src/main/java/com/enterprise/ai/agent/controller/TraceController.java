package com.enterprise.ai.agent.controller;

import com.enterprise.ai.agent.tool.log.ToolCallLogEntity;
import com.enterprise.ai.agent.tool.log.ToolCallLogService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/traces")
@RequiredArgsConstructor
public class TraceController {

    private static final TypeReference<List<Map<String, Object>>> CANDIDATES_TYPE = new TypeReference<>() {};
    private final ToolCallLogService toolCallLogService;
    private final ObjectMapper objectMapper;

    @GetMapping("/{traceId}")
    public ResponseEntity<TraceDetailResponse> detail(@PathVariable String traceId) {
        List<ToolCallLogEntity> logs = toolCallLogService.getTraceLogs(traceId);
        if (logs.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        List<TraceNode> nodes = logs.stream().map(this::toNode).toList();
        return ResponseEntity.ok(new TraceDetailResponse(traceId, nodes));
    }

    @GetMapping("/recent")
    public ResponseEntity<List<ToolCallLogService.TraceSummary>> recent(
            @RequestParam(required = false) String userId,
            @RequestParam(defaultValue = "30") int days,
            @RequestParam(defaultValue = "20") int limit) {
        return ResponseEntity.ok(toolCallLogService.listRecentTraces(userId, limit, days));
    }

    private TraceNode toNode(ToolCallLogEntity log) {
        return new TraceNode(
                log.getId(),
                log.getTraceId(),
                log.getAgentName(),
                log.getToolName(),
                log.getArgsJson(),
                log.getResultSummary(),
                Boolean.TRUE.equals(log.getSuccess()),
                log.getErrorCode(),
                log.getElapsedMs(),
                log.getTokenCost(),
                parseRetrieval(log.getRetrievalTraceJson()),
                log.getCreateTime()
        );
    }

    private List<Map<String, Object>> parseRetrieval(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(raw, CANDIDATES_TYPE);
        } catch (Exception ignored) {
            return List.of(Map.of("raw", raw));
        }
    }

    public record TraceDetailResponse(String traceId, List<TraceNode> nodes) {}

    public record TraceNode(
            Long id,
            String traceId,
            String agentName,
            String toolName,
            String argsJson,
            String resultSummary,
            boolean success,
            String errorCode,
            Integer elapsedMs,
            Integer tokenCost,
            List<Map<String, Object>> retrievalCandidates,
            java.time.LocalDateTime createdAt
    ) {}
}
