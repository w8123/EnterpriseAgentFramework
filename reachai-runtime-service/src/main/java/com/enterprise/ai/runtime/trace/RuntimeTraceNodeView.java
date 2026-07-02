package com.enterprise.ai.runtime.trace;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

public record RuntimeTraceNodeView(
        Long id,
        String source,
        String traceId,
        String agentName,
        String toolName,
        String spanType,
        String spanId,
        String parentSpanId,
        String nodeId,
        String runtimeType,
        String argsJson,
        String resultSummary,
        boolean success,
        String errorCode,
        Integer elapsedMs,
        Integer tokenCost,
        List<Map<String, Object>> retrievalCandidates,
        LocalDateTime createdAt) {
}
