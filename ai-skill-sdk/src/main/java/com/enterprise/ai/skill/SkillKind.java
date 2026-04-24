package com.enterprise.ai.skill;

/**
 * Skill 形态。Phase 2.0 仅实现 {@link #SUB_AGENT}，另外两态占位，等 Phase 2.2 落地。
 */
public enum SkillKind {

    /** 子 Agent 封装：独立 systemPrompt + 子集 tool。 */
    SUB_AGENT,

    /** 固定编排（DSL / DAG）。Phase 2.2 启用。 */
    WORKFLOW,

    /** 单 Tool 装饰（前置校验 / 后置整形 / HITL）。Phase 2.2 启用。 */
    AUGMENTED_TOOL
}
