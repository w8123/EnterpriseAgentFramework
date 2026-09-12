package com.enterprise.ai.runtime.trace;

import java.time.LocalDateTime;
import java.util.Map;

/** Trace-owned statistics; callers receive no invocation payload or persistence objects. */
public interface RuntimeToolUsageStatisticsQuery {
    Summary summarizeSince(LocalDateTime from);

    record Summary(int logCount, long traceCount, long retrievalTraceCount,
                   Map<String, Long> intentCounts, Map<String, Long> agentCounts) {
        public Summary {
            intentCounts = Map.copyOf(intentCounts);
            agentCounts = Map.copyOf(agentCounts);
        }
    }
}
