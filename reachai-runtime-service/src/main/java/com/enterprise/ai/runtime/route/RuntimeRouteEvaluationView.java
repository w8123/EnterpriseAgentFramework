package com.enterprise.ai.runtime.route;

import java.util.Map;

public record RuntimeRouteEvaluationView(
        int days,
        int logCount,
        long traceCount,
        long retrievalTraceCount,
        Map<String, Long> intentCounts,
        Map<String, Long> agentCounts,
        boolean intentClassifierReady,
        boolean domainClassifierReady,
        String recommendation) {

    public RuntimeRouteEvaluationView {
        intentCounts = intentCounts == null ? Map.of() : Map.copyOf(intentCounts);
        agentCounts = agentCounts == null ? Map.of() : Map.copyOf(agentCounts);
    }
}
