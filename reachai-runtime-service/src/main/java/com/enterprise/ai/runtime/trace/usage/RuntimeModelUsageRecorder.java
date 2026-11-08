package com.enterprise.ai.runtime.trace.usage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Keeps one row per model call so 「AI 使用情况」 can estimate cost per person, system and model.
 * Recording never breaks the run: a missing table or DB error only logs a warning.
 */
@Component
public class RuntimeModelUsageRecorder {

    public static final String SOURCE_AGENT = "AGENT";
    public static final String SOURCE_AGENT_COMPACTION = "AGENT_COMPACTION";
    public static final String SOURCE_WORKFLOW_NODE = "WORKFLOW_NODE";
    public static final String SOURCE_WORKFLOW_AUTHORING = "WORKFLOW_AUTHORING";

    private static final Logger log = LoggerFactory.getLogger(RuntimeModelUsageRecorder.class);

    private final RuntimeModelUsageMapper mapper;

    public RuntimeModelUsageRecorder(RuntimeModelUsageMapper mapper) {
        this.mapper = mapper;
    }

    public void record(String traceId, String source, String modelInstanceId, Integer inputTokens, Integer outputTokens) {
        int input = inputTokens == null ? 0 : Math.max(0, inputTokens);
        int output = outputTokens == null ? 0 : Math.max(0, outputTokens);
        if (!StringUtils.hasText(modelInstanceId) || (input == 0 && output == 0)) {
            return;
        }
        try {
            RuntimeModelUsageEntity row = new RuntimeModelUsageEntity();
            row.setTraceId(clip(traceId, 64));
            row.setUsageSource(StringUtils.hasText(source) ? clip(source, 32) : SOURCE_AGENT);
            row.setModelInstanceId(clip(modelInstanceId, 64));
            row.setInputTokens(input);
            row.setOutputTokens(output);
            row.setCreatedAt(LocalDateTime.now());
            mapper.insert(row);
        } catch (RuntimeException failure) {
            log.warn("[ModelUsage] not recorded modelInstanceId={} reason={}", modelInstanceId,
                    failure.getClass().getSimpleName());
        }
    }

    public List<RuntimeModelUsageRow> usage(String projectCode, int days) {
        int safeDays = days <= 0 ? 30 : Math.min(days, 90);
        String project = StringUtils.hasText(projectCode) ? projectCode.trim() : null;
        List<RuntimeModelUsageRow> rows = mapper.selectUsage(LocalDateTime.now().minusDays(safeDays), project);
        return rows == null ? List.of() : rows;
    }

    /** Business runs for the audit export, newest first, at most {@code limit} (capped at 50 000). */
    public List<RuntimeAuditRunRow> auditRuns(String projectCode, int days, int limit) {
        int safeDays = days <= 0 ? 30 : Math.min(days, 90);
        int safeLimit = limit <= 0 ? 10_000 : Math.min(limit, 50_000);
        String project = StringUtils.hasText(projectCode) ? projectCode.trim() : null;
        List<RuntimeAuditRunRow> rows = mapper.selectAuditRuns(LocalDateTime.now().minusDays(safeDays), project, safeLimit);
        return rows == null ? List.of() : rows;
    }

    static String clip(String value, int max) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.length() <= max ? trimmed : trimmed.substring(0, max);
    }

    /** Callback a model wrapper uses to report each finished round. */
    @FunctionalInterface
    public interface Listener {
        void onUsage(String modelInstanceId, Integer inputTokens, Integer outputTokens);
    }
}
