package com.enterprise.ai.runtime.route;

import com.enterprise.ai.runtime.trace.RuntimeToolUsageStatisticsQuery;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class RuntimeRouteEvaluationService {

    private final RuntimeToolUsageStatisticsQuery usageStatistics;

    public RuntimeRouteEvaluationView evaluate(int days) {
        int safeDays = Math.max(1, Math.min(days, 90));
        LocalDateTime from = LocalDateTime.now().minusDays(safeDays);
        var evidence = usageStatistics.summarizeSince(from);
        long traceCount = evidence.traceCount();
        long retrievalTraceCount = evidence.retrievalTraceCount();
        Map<String, Long> intentCounts = evidence.intentCounts();
        Map<String, Long> agentCounts = evidence.agentCounts();
        boolean intentClassifierReady = traceCount >= 1_000 && intentCounts.size() >= 2;

        return new RuntimeRouteEvaluationView(
                safeDays,
                evidence.logCount(),
                traceCount,
                retrievalTraceCount,
                intentCounts,
                agentCounts,
                intentClassifierReady,
                recommendation(intentClassifierReady));
    }

    private String recommendation(boolean intentReady) {
        if (!intentReady) {
            return "样本仍偏少，建议继续使用现有 LLM 意图识别与 RetrievalScope，优先积累 trace。";
        }
        return "意图标签样本已足够，可试点规则 + 小模型两阶段 IntentClassifier。";
    }
}
