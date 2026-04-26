package com.enterprise.ai.skill;

/**
 * Skill 形态。Phase 2.0 实现 {@link #SUB_AGENT}；Phase 2.x 实现 {@link #INTERACTIVE_FORM}；
 * {@link #WORKFLOW} / {@link #AUGMENTED_TOOL} 占位待 Phase 2.2+。
 */
public enum SkillKind {

    /** 子 Agent 封装：独立 systemPrompt + 子集 tool。 */
    SUB_AGENT,

    /** 固定编排（DSL / DAG）。Phase 2.2 启用。 */
    WORKFLOW,

    /** 单 Tool 装饰（前置校验 / 后置整形 / HITL）。Phase 2.2 启用。 */
    AUGMENTED_TOOL,

    /**
     * 交互式表单：确定性槽填充 + 挂起/恢复 + 前端 UI 原语。
     * Phase 2.x：与 SubAgent 并列，用于「调写接口前多轮收集与确认」类场景。
     */
    INTERACTIVE_FORM
}
