package com.enterprise.ai.runtime.trace;

import java.time.LocalDateTime;

public record RuntimeTraceSummaryView(
        String traceId,
        String sessionId,
        String userId,
        String agentName,
        String intentType,
        int callCount,
        long successCount,
        LocalDateTime startedAt,
        LocalDateTime endedAt) {
}
