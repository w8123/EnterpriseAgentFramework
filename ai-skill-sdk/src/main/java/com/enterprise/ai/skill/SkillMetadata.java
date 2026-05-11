package com.enterprise.ai.skill;

import java.util.Map;

/**
 * 粗粒度能力的元数据（产品语义：Capability metadata）。类型名为 {@code SkillMetadata} 仅为历史兼容。
 * Phase 2.0 仅消费 {@link #sideEffect} 与 {@link #timeoutMs}；
 * {@link #hitl} / {@link #retryLimit} 字段已预留，等 Phase 4.1 审批台与 2.2 AugmentedTool 启用。
 *
 * @param version      能力版本号
 * @param sideEffect   副作用等级
 * @param hitl         HITL 策略，Phase 2.0 仅落表不执行
 * @param timeoutMs    单次执行超时（能力内部跑多步 ReAct 时整体预算）
 * @param retryLimit   失败重试次数，Phase 2.0 默认 0（不重试）
 * @param tags         业务标签，能力挖掘 / 画布过滤会用
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
