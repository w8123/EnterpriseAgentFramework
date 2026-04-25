package com.enterprise.ai.agent.tool.log;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.enterprise.ai.agent.config.ToolCallLogProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.LocalDate;
import java.util.Map;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Tool 调用审计日志写入服务。
 * <p>
 * 调用方埋点一次 {@link #record(ToolExecutionContext, String, java.util.Map, Object, boolean, String, long)}，
 * 根据配置同步或异步写入 {@code tool_call_log}；任何写入异常都吞掉（只打日志），不拖累 Agent 主链路。
 */
@Slf4j
@Service
public class ToolCallLogService {

    private final ToolCallLogMapper mapper;
    private final ToolCallLogProperties properties;
    private final ObjectMapper objectMapper;

    public ToolCallLogService(ToolCallLogMapper mapper,
                              ToolCallLogProperties properties,
                              ObjectMapper objectMapper) {
        this.mapper = mapper;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    /**
     * 记录一次 tool 调用。
     */
    public void record(ToolExecutionContext context,
                       String toolName,
                       Map<String, Object> args,
                       Object result,
                       boolean success,
                       String errorCode,
                       long elapsedMs) {
        record(context, toolName, args, result, success, errorCode, elapsedMs, null);
    }

    /**
     * 记录一次 tool 或内部 Trace 节点；{@code tokenCost} 用于 LLM 等可统计 token 的跨度。
     */
    public void record(ToolExecutionContext context,
                       String toolName,
                       Map<String, Object> args,
                       Object result,
                       boolean success,
                       String errorCode,
                       long elapsedMs,
                       Integer tokenCost) {
        if (!properties.isEnabled()) {
            return;
        }
        ToolCallLogEntity entity = new ToolCallLogEntity();
        entity.setTraceId(context == null ? null : context.getTraceId());
        entity.setSessionId(context == null ? null : context.getSessionId());
        entity.setUserId(context == null ? null : context.getUserId());
        entity.setAgentName(context == null ? null : context.getAgentName());
        entity.setIntentType(context == null ? null : context.getIntentType());
        entity.setToolName(toolName);
        entity.setArgsJson(truncate(toJson(args), properties.getArgsMaxChars()));
        entity.setResultSummary(truncate(stringify(result), properties.getResultMaxChars()));
        entity.setSuccess(success);
        entity.setErrorCode(errorCode);
        entity.setElapsedMs((int) Math.min(elapsedMs, Integer.MAX_VALUE));
        entity.setTokenCost(tokenCost);
        entity.setRetrievalTraceJson(context == null ? null : context.getRetrievalTraceJson());
        entity.setCreateTime(LocalDateTime.now());

        if (properties.isAsync()) {
            persistAsync(entity);
        } else {
            persist(entity);
        }
    }

    @Async
    public void persistAsync(ToolCallLogEntity entity) {
        persist(entity);
    }

    private void persist(ToolCallLogEntity entity) {
        try {
            mapper.insert(entity);
        } catch (Exception ex) {
            log.warn("[ToolCallLog] 写入失败: toolName={}, err={}", entity.getToolName(), ex.toString());
        }
    }

    private String toJson(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            return String.valueOf(value);
        }
    }

    private String stringify(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof CharSequence s) {
            return s.toString();
        }
        return toJson(value);
    }

    private String truncate(String s, int max) {
        if (s == null || max <= 0 || s.length() <= max) {
            return s;
        }
        return s.substring(0, max) + "...[truncated]";
    }

    public SkillMetricsView getSkillMetrics(String skillName, int days) {
        int safeDays = Math.max(1, Math.min(days, 30));
        LocalDateTime from = LocalDateTime.now().minusDays(safeDays);
        List<ToolCallLogEntity> logs = mapper.selectList(new LambdaQueryWrapper<ToolCallLogEntity>()
                .eq(ToolCallLogEntity::getToolName, skillName)
                .ge(ToolCallLogEntity::getCreateTime, from)
                .orderByAsc(ToolCallLogEntity::getCreateTime));
        if (logs.isEmpty()) {
            return SkillMetricsView.empty(safeDays);
        }
        List<Integer> latencies = logs.stream()
                .map(ToolCallLogEntity::getElapsedMs)
                .filter(v -> v != null && v >= 0)
                .toList();
        List<Integer> tokenCosts = logs.stream()
                .map(ToolCallLogEntity::getTokenCost)
                .filter(v -> v != null && v >= 0)
                .toList();
        long successCount = logs.stream().filter(x -> Boolean.TRUE.equals(x.getSuccess())).count();
        List<SkillMetricPoint> trends = buildDailyTrend(logs);
        return new SkillMetricsView(
                percentile(latencies, 50),
                percentile(latencies, 95),
                percentile(tokenCosts, 50),
                percentile(tokenCosts, 95),
                logs.size(),
                logs.isEmpty() ? 0D : ((double) successCount / logs.size()),
                trends
        );
    }

    /**
     * 计算文档《Skill-评估指标口径》里的 HitRate / ReplacementRate。
     * <p>
     * 口径：
     * <ul>
     *   <li><b>hitRate</b> = 窗口内"有调用的天数 / 窗口天数"，把"是否有稳定调用"量化为按天覆盖率（0..1）；</li>
     *   <li><b>replacementRate</b> = {@code skillCalls / (skillCalls + 同意图下多步 Tool 链 trace 数)}；
     *       其中"多步 Tool 链 trace"定义为：intent_type ∈ Skill 自身用过的 intent 集合、
     *       同 trace_id 内 <b>distinct tool_name ≥ 2</b>、且该 trace 里 <b>不包含本 Skill</b>。</li>
     * </ul>
     * 两者都在 Skill 无任何调用时返回 null，避免给出伪造的"1.0"。
     */
    public CoverageMetrics computeCoverageMetrics(String skillName, int days) {
        int safeDays = Math.max(1, Math.min(days, 30));
        LocalDateTime from = LocalDateTime.now().minusDays(safeDays);

        List<ToolCallLogEntity> skillLogs = mapper.selectList(new LambdaQueryWrapper<ToolCallLogEntity>()
                .eq(ToolCallLogEntity::getToolName, skillName)
                .ge(ToolCallLogEntity::getCreateTime, from));
        if (skillLogs.isEmpty()) {
            return new CoverageMetrics(null, null, 0, 0);
        }

        long activeDays = skillLogs.stream()
                .map(ToolCallLogEntity::getCreateTime)
                .filter(java.util.Objects::nonNull)
                .map(LocalDateTime::toLocalDate)
                .distinct()
                .count();
        double hitRate = (double) activeDays / safeDays;

        Set<String> skillIntents = skillLogs.stream()
                .map(ToolCallLogEntity::getIntentType)
                .filter(x -> x != null && !x.isBlank())
                .collect(Collectors.toSet());
        Set<String> skillTraceIds = skillLogs.stream()
                .map(ToolCallLogEntity::getTraceId)
                .filter(x -> x != null && !x.isBlank())
                .collect(Collectors.toSet());
        int skillCalls = skillLogs.size();

        long multiToolTraceCount;
        if (skillIntents.isEmpty()) {
            // 无意图标注时，分母退化成"窗口内任意多步 Tool 链 trace 数"，保证指标可算
            multiToolTraceCount = countMultiToolTraces(from, null, skillName, skillTraceIds);
        } else {
            multiToolTraceCount = countMultiToolTraces(from, skillIntents, skillName, skillTraceIds);
        }

        long denominator = skillCalls + multiToolTraceCount;
        Double replacementRate = denominator == 0 ? null : (double) skillCalls / denominator;
        return new CoverageMetrics(hitRate, replacementRate, skillCalls, multiToolTraceCount);
    }

    /**
     * 数"同意图下，且不包含指定 Skill 的多步 Tool 链 trace"。
     * 多步 = 该 trace 内 distinct tool_name ≥ 2。
     */
    private long countMultiToolTraces(LocalDateTime from,
                                      Set<String> intents,
                                      String excludeSkillName,
                                      Set<String> excludeTraceIds) {
        LambdaQueryWrapper<ToolCallLogEntity> w = new LambdaQueryWrapper<ToolCallLogEntity>()
                .ge(ToolCallLogEntity::getCreateTime, from)
                .isNotNull(ToolCallLogEntity::getTraceId);
        if (intents != null && !intents.isEmpty()) {
            w.in(ToolCallLogEntity::getIntentType, intents);
        }
        // 只取 trace 分组需要的两列，降低网络/内存开销
        w.select(ToolCallLogEntity::getTraceId, ToolCallLogEntity::getToolName);
        List<ToolCallLogEntity> rows = mapper.selectList(w);

        Map<String, Set<String>> traceToTools = new java.util.HashMap<>();
        Set<String> tracesContainingSkill = new HashSet<>();
        for (ToolCallLogEntity r : rows) {
            String traceId = r.getTraceId();
            String toolName = r.getToolName();
            if (traceId == null || toolName == null) continue;
            if (excludeSkillName.equals(toolName)) {
                tracesContainingSkill.add(traceId);
                continue;
            }
            traceToTools.computeIfAbsent(traceId, k -> new HashSet<>()).add(toolName);
        }

        long count = 0;
        for (Map.Entry<String, Set<String>> e : traceToTools.entrySet()) {
            if (e.getValue().size() < 2) continue;
            if (tracesContainingSkill.contains(e.getKey())) continue;
            if (excludeTraceIds != null && excludeTraceIds.contains(e.getKey())) continue;
            count++;
        }
        return count;
    }

    public List<ToolCallLogEntity> getTraceLogs(String traceId) {
        return mapper.selectList(new LambdaQueryWrapper<ToolCallLogEntity>()
                .eq(ToolCallLogEntity::getTraceId, traceId)
                .orderByAsc(ToolCallLogEntity::getId));
    }

    public List<TraceSummary> listRecentTraces(String userId, int limit, int days) {
        int safeLimit = Math.max(1, Math.min(limit, 200));
        int safeDays = Math.max(1, Math.min(days, 30));
        // DB 层兜底：最多拉 safeLimit * 50 条最近记录，避免日志爆炸时把全表加载到内存
        int rawRowCap = Math.max(safeLimit * 50, 500);
        LambdaQueryWrapper<ToolCallLogEntity> wrapper = new LambdaQueryWrapper<ToolCallLogEntity>()
                .isNotNull(ToolCallLogEntity::getTraceId)
                .ge(ToolCallLogEntity::getCreateTime, LocalDateTime.now().minusDays(safeDays))
                .orderByDesc(ToolCallLogEntity::getId)
                .last("limit " + rawRowCap);
        if (userId != null && !userId.isBlank()) {
            wrapper.eq(ToolCallLogEntity::getUserId, userId.trim());
        }
        List<ToolCallLogEntity> logs = mapper.selectList(wrapper);
        LinkedHashMap<String, List<ToolCallLogEntity>> grouped = new LinkedHashMap<>();
        for (ToolCallLogEntity log : logs) {
            String traceId = log.getTraceId();
            if (traceId == null) {
                continue;
            }
            // 已达 safeLimit 个 group 后，只往已存在的 group 追加记录；不再新开 group
            if (!grouped.containsKey(traceId) && grouped.size() >= safeLimit) {
                continue;
            }
            grouped.computeIfAbsent(traceId, k -> new ArrayList<>()).add(log);
        }
        return grouped.entrySet().stream()
                .map(e -> {
                    List<ToolCallLogEntity> traceLogs = e.getValue();
                    traceLogs.sort(Comparator.comparing(ToolCallLogEntity::getId));
                    ToolCallLogEntity first = traceLogs.get(0);
                    ToolCallLogEntity last = traceLogs.get(traceLogs.size() - 1);
                    long success = traceLogs.stream().filter(x -> Boolean.TRUE.equals(x.getSuccess())).count();
                    return new TraceSummary(
                            e.getKey(),
                            first.getSessionId(),
                            first.getUserId(),
                            first.getAgentName(),
                            first.getIntentType(),
                            traceLogs.size(),
                            success,
                            first.getCreateTime(),
                            last.getCreateTime()
                    );
                })
                .toList();
    }

    private List<SkillMetricPoint> buildDailyTrend(List<ToolCallLogEntity> logs) {
        Map<LocalDate, List<ToolCallLogEntity>> grouped = logs.stream()
                .filter(x -> x.getCreateTime() != null)
                .collect(Collectors.groupingBy(x -> x.getCreateTime().toLocalDate()));
        return grouped.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(e -> {
                    List<ToolCallLogEntity> rows = e.getValue();
                    List<Integer> latencies = rows.stream().map(ToolCallLogEntity::getElapsedMs)
                            .filter(v -> v != null && v >= 0).toList();
                    List<Integer> tokens = rows.stream().map(ToolCallLogEntity::getTokenCost)
                            .filter(v -> v != null && v >= 0).toList();
                    long success = rows.stream().filter(x -> Boolean.TRUE.equals(x.getSuccess())).count();
                    return new SkillMetricPoint(
                            e.getKey().toString(),
                            rows.size(),
                            rows.isEmpty() ? 0D : (double) success / rows.size(),
                            percentile(latencies, 95),
                            percentile(tokens, 95)
                    );
                })
                .toList();
    }

    private int percentile(List<Integer> values, int p) {
        if (values == null || values.isEmpty()) {
            return 0;
        }
        List<Integer> sorted = values.stream().sorted().toList();
        int idx = (int) Math.ceil((p / 100D) * sorted.size()) - 1;
        idx = Math.max(0, Math.min(idx, sorted.size() - 1));
        return sorted.get(idx);
    }

    public record SkillMetricsView(
            int p50LatencyMs,
            int p95LatencyMs,
            int p50TokenCost,
            int p95TokenCost,
            int callCount,
            double successRate,
            List<SkillMetricPoint> trends
    ) {
        public static SkillMetricsView empty(int days) {
            List<SkillMetricPoint> points = new ArrayList<>();
            for (int i = days - 1; i >= 0; i--) {
                points.add(new SkillMetricPoint(LocalDate.now().minusDays(i).toString(), 0, 0D, 0, 0));
            }
            return new SkillMetricsView(0, 0, 0, 0, 0, 0D, points);
        }
    }

    public record SkillMetricPoint(
            String day,
            int callCount,
            double successRate,
            int p95LatencyMs,
            int p95TokenCost
    ) {}

    public record TraceSummary(
            String traceId,
            String sessionId,
            String userId,
            String agentName,
            String intentType,
            int callCount,
            long successCount,
            LocalDateTime startedAt,
            LocalDateTime endedAt
    ) {}

    /**
     * Skill 覆盖类指标（非延迟/token 类）。hitRate/replacementRate 可为 null，表示窗口内无调用不可算。
     */
    public record CoverageMetrics(
            Double hitRate,
            Double replacementRate,
            int skillCalls,
            long multiToolTraces
    ) {}
}
