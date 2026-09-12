package com.enterprise.ai.runtime.trace;

import java.time.LocalDateTime;
import java.util.List;

/** 运行分析所需的 Trace 记录。JSON 保留原文，由使用方按自己的视图解释。 */
public record RuntimeTraceRecords(List<Span> spans, List<ToolCall> toolCalls) {

    public RuntimeTraceRecords {
        spans = List.copyOf(spans);
        toolCalls = List.copyOf(toolCalls);
    }

    public record Span(
            Long id,
            String traceId,
            String agentName,
            String spanId,
            String parentSpanId,
            String spanType,
            String runtimeType,
            String nodeId,
            String toolName,
            String status,
            String inputSummary,
            String outputSummary,
            String metadataJson,
            String errorCode,
            String errorMessage,
            Integer latencyMs,
            Integer tokenCost,
            LocalDateTime startedAt,
            LocalDateTime endedAt,
            LocalDateTime createdAt) {
    }

    public record ToolCall(
            Long id,
            String traceId,
            String toolName,
            String agentName,
            String sessionId,
            String userId,
            String intentType,
            String projectCode,
            Boolean success,
            String argsJson,
            String resultSummary,
            String errorCode,
            Integer elapsedMs,
            Integer tokenCost,
            String retrievalTraceJson,
            LocalDateTime createTime) {
    }
}
