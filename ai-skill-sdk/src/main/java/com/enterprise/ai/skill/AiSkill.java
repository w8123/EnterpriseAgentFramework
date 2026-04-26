package com.enterprise.ai.skill;

import java.util.Collections;
import java.util.List;

/**
 * 复合技能 — {@link AiTool} 的上位封装。
 * <p>
 * 动机：多步业务流程直接挂在主 Agent 的 ReAct 循环里时，LLM 经常选错/遗漏工具；
 * 把这些"多步组合"打包成 Skill 暴露给主 Agent，就只需要做一次决策，决策空间由 N 收敛到 1。
 * <p>
 * 从调用者（主 Agent）视角看，Skill 和 Tool 一样：都有 name/description/parameters/execute。
 * 差异发生在 Executor：AiTool 走 HTTP/Java 直接执行；AiSkill 会被 Executor 进一步展开为
 * 子 ReAct 循环 / 固定 Workflow / 装饰 Tool。
 * <p>
 * 检索（ToolRetrieval）层统一把 AiTool 与 AiSkill 嵌入到同一个 Milvus collection，
 * 靠 scalar 字段 {@code kind} 区分形态。
 */
public interface AiSkill extends AiTool {

    /** Skill 形态：{@link SkillKind#SUB_AGENT}、{@link SkillKind#INTERACTIVE_FORM} 等。 */
    SkillKind kind();

    /** Skill 元数据（版本 / 副作用 / HITL / 超时）。 */
    SkillMetadata metadata();

    /**
     * 该 Skill 内部可能调用的 Tool 名称白名单（主要供 SubAgent 形态使用）。
     * 用于：
     * <ol>
     *   <li>子 Agent 构建时只注入这些 Tool（收敛决策空间）；</li>
     *   <li>依赖分析 / 影响面分析（Tool 下线时哪些 Skill 会受影响）。</li>
     * </ol>
     */
    default List<String> dependsOnTools() {
        return Collections.emptyList();
    }
}
