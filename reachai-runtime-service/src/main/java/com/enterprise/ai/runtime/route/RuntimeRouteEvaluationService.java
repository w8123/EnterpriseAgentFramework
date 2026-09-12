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
        boolean domainClassifierReady = traceCount >= 500 && agentCounts.size() >= 3 && retrievalTraceCount > traceCount / 3;

        return new RuntimeRouteEvaluationView(
                safeDays,
                evidence.logCount(),
                traceCount,
                retrievalTraceCount,
                intentCounts,
                agentCounts,
                intentClassifierReady,
                domainClassifierReady,
                recommendation(traceCount, retrievalTraceCount, intentCounts, agentCounts,
                        intentClassifierReady, domainClassifierReady));
    }

    private String recommendation(long traceCount,
                                  long retrievalTraceCount,
                                  Map<String, Long> intentCounts,
                                  Map<String, Long> agentCounts,
                                  boolean intentReady,
                                  boolean domainReady) {
        if (!intentReady && !domainReady) {
            return "样本仍偏少，建议继续使用现有 LLM 意图识别与 RetrievalScope，优先积累 trace。";
        }
        if (domainReady && !intentReady) {
            return "跨 Agent / 召回样本已较多，可先试点 DomainClassifier 作为 Tool Retrieval 前置过滤。";
        }
        if (intentReady && !domainReady) {
            return "意图标签样本已足够，可试点规则 + 小模型两阶段 IntentClassifier。";
        }
        return "意图与领域样本都已达到试点阈值，建议先做离线评估集，再灰度接入路由链路。"
                + " traceCount=" + traceCount
                + ", retrievalTraceCount=" + retrievalTraceCount
                + ", intents=" + intentCounts.size()
                + ", agents=" + agentCounts.size();
    }
}
