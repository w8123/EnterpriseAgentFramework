package com.enterprise.ai.skill;

import java.util.Map;

/**
 * Skill 元数据，Phase 2.0 仅消费 {@link #sideEffect} 与 {@link #timeoutMs}；
 * {@link #hitl} / {@link #retryLimit} 字段已预留，等 Phase 4.1 审批台与 2.2 AugmentedTool 启用。
 *
 * @param version      Skill 版本号（Skill 上线后演进需要）
 * @param sideEffect   副作用等级
 * @param hitl         HITL 策略，Phase 2.0 仅落表不执行
 * @param timeoutMs    单次执行超时（Skill 内部跑多步 ReAct 时整体预算）
 * @param retryLimit   失败重试次数，Phase 2.0 默认 0（不重试）
 * @param tags         业务标签，Skill Mining / 画布过滤会用
 */
public record SkillMetadata(
        String version,
        SideEffectLevel sideEffect,
        HitlPolicy hitl,
        int timeoutMs,
        int retryLimit,
        Map<String, String> tags
) {

    public static SkillMetadata defaultFor(SideEffectLevel sideEffect) {
        return new SkillMetadata(
                "1.0.0",
                sideEffect == null ? SideEffectLevel.WRITE : sideEffect,
                HitlPolicy.NEVER,
                60_000,
                0,
                Map.of()
        );
    }
}
