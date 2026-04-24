package com.enterprise.ai.agent.mining;

import com.enterprise.ai.agent.tool.log.ToolCallLogService;
import com.enterprise.ai.agent.tools.definition.ToolDefinitionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

@Slf4j
@Component
@RequiredArgsConstructor
public class SkillEvaluationScheduler {
    private final ToolDefinitionService toolDefinitionService;
    private final ToolCallLogService toolCallLogService;
    private final SkillEvalSnapshotMapper snapshotMapper;
    private final SkillDraftMapper skillDraftMapper;

    // MVP 阈值，后续迁到 config
    private static final int MIN_CALL_COUNT_FOR_JUDGMENT = 20;
    private static final double SUCCESS_RATE_BASELINE = 0.80D;
    private static final double SUCCESS_RATE_ROLLBACK = 0.70D;
    private static final int TOKEN_BUDGET_BASELINE = 2000;
    private static final int TOKEN_BUDGET_ROLLBACK = 4000;

    /**
     * 每天凌晨 2 点做一次 Skill 评估快照。
     * <p>
     * 指标完整口径见 {@code docs/Skill-评估指标口径.md}。本 MVP 实现只能精确拿到
     * {@code successRate/p95TokenCost}；{@code hitRate/replacementRate} 依赖
     * 同一 trace 内是否存在「替代 Skill 的工具链」，需要 join 另一套标注数据，
     * 这里故意置 null，避免前端读到被伪造成 1.0 的假指标。
     */
    @Scheduled(cron = "${ai.skill-eval.cron:0 0 2 * * ?}")
    public void evaluateSkills() {
        int ok = 0;
        for (var skill : toolDefinitionService.listSkills()) {
            try {
                evaluateOne(skill.getName());
                ok++;
            } catch (Exception ex) {
                log.warn("[SkillEvaluationScheduler] 评估 skill={} 失败: {}", skill.getName(), ex.toString());
            }
        }
        log.info("[SkillEvaluationScheduler] 完成 skill 自动评估, 成功 {} 个", ok);
    }

    private void evaluateOne(String skillName) {
        ToolCallLogService.SkillMetricsView metrics = toolCallLogService.getSkillMetrics(skillName, 7);
        ToolCallLogService.CoverageMetrics coverage = toolCallLogService.computeCoverageMetrics(skillName, 7);
        String status = deriveStatus(metrics);

        SkillEvalSnapshotEntity snapshot = new SkillEvalSnapshotEntity();
        snapshot.setSkillName(skillName);
        snapshot.setCallCount(metrics.callCount());
        snapshot.setHitRate(coverage.hitRate());
        snapshot.setReplacementRate(coverage.replacementRate());
        snapshot.setSuccessRateDiff(metrics.callCount() > 0
                ? metrics.successRate() - SUCCESS_RATE_BASELINE
                : null);
        // token_savings = baseline - p95；callCount=0 时为 null 而非虚高的 2000
        snapshot.setTokenSavings(metrics.callCount() > 0
                ? Math.max(0, TOKEN_BUDGET_BASELINE - metrics.p95TokenCost())
                : null);
        snapshot.setStatus(status);
        snapshot.setNote(String.format(
                "auto-eval,7d,minCall=%d,multiToolTraces=%d",
                MIN_CALL_COUNT_FOR_JUDGMENT, coverage.multiToolTraces()));
        snapshot.setCreateTime(LocalDateTime.now());
        snapshotMapper.insert(snapshot);

        if ("ROLLBACK_CANDIDATE".equals(status)) {
            skillDraftMapper.selectList(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<SkillDraftEntity>()
                    .eq(SkillDraftEntity::getName, skillName))
                    .forEach(draft -> {
                        // 已经丢弃/回滚过的草稿不重复打标
                        if ("DISCARDED".equals(draft.getStatus())
                                || "ROLLBACK_CANDIDATE".equals(draft.getStatus())) {
                            return;
                        }
                        draft.setStatus("ROLLBACK_CANDIDATE");
                        draft.setReviewNote("auto marked by evaluator: "
                                + "successRate=" + fmt(metrics.successRate())
                                + ", p95Token=" + metrics.p95TokenCost());
                        draft.setUpdateTime(LocalDateTime.now());
                        skillDraftMapper.updateById(draft);
                    });
        }
    }

    private String deriveStatus(ToolCallLogService.SkillMetricsView metrics) {
        if (metrics.callCount() < MIN_CALL_COUNT_FOR_JUDGMENT) {
            return "OBSERVE";
        }
        if (metrics.successRate() < SUCCESS_RATE_ROLLBACK
                || metrics.p95TokenCost() > TOKEN_BUDGET_ROLLBACK) {
            return "ROLLBACK_CANDIDATE";
        }
        return "HEALTHY";
    }

    private static String fmt(double v) {
        return String.format("%.3f", v);
    }
}
