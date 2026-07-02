package com.enterprise.ai.runtime.route;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.enterprise.ai.runtime.trace.RuntimeToolCallLogEntity;
import com.enterprise.ai.runtime.trace.RuntimeToolCallLogMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class RuntimeRouteEvaluationService {

    private final RuntimeToolCallLogMapper toolLogMapper;

    public RuntimeRouteEvaluationView evaluate(int days) {
        int safeDays = Math.max(1, Math.min(days, 90));
        LocalDateTime from = LocalDateTime.now().minusDays(safeDays);
        List<RuntimeToolCallLogEntity> logs = toolLogMapper.selectList(new LambdaQueryWrapper<RuntimeToolCallLogEntity>()
                .ge(RuntimeToolCallLogEntity::getCreateTime, from));
        long traceCount = logs.stream()
                .map(RuntimeToolCallLogEntity::getTraceId)
                .filter(StringUtils::hasText)
                .distinct()
                .count();
        long retrievalTraceCount = logs.stream()
                .filter(log -> StringUtils.hasText(log.getRetrievalTraceJson()))
                .map(RuntimeToolCallLogEntity::getTraceId)
                .filter(StringUtils::hasText)
                .distinct()
                .count();
        Map<String, Long> intentCounts = countBy(logs, RuntimeToolCallLogEntity::getIntentType);
        Map<String, Long> agentCounts = countBy(logs, RuntimeToolCallLogEntity::getAgentName);
        boolean intentClassifierReady = traceCount >= 1_000 && intentCounts.size() >= 2;
        boolean domainClassifierReady = traceCount >= 500 && agentCounts.size() >= 3 && retrievalTraceCount > traceCount / 3;

        return new RuntimeRouteEvaluationView(
                safeDays,
                logs.size(),
                traceCount,
                retrievalTraceCount,
                intentCounts,
                agentCounts,
                intentClassifierReady,
                domainClassifierReady,
                recommendation(traceCount, retrievalTraceCount, intentCounts, agentCounts,
                        intentClassifierReady, domainClassifierReady));
    }

    private Map<String, Long> countBy(List<RuntimeToolCallLogEntity> logs,
                                     java.util.function.Function<RuntimeToolCallLogEntity, String> getter) {
        return logs.stream()
                .map(getter)
                .filter(StringUtils::hasText)
                .collect(Collectors.groupingBy(value -> value, LinkedHashMap::new, Collectors.counting()));
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
