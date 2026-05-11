package com.enterprise.ai.skill;

import java.util.Collections;
import java.util.List;

/**
 * 复合能力运行时接口 — {@link AiTool} 的上位封装（产品语义：<b>Capability</b>）。
 * <p>
 * 接口名为 {@code AiSkill} 仅为历史兼容；新代码与文档请使用「能力 / Capability」表述。
 * <p>
 * 动机：多步业务流程直接挂在主 Agent 的 ReAct 循环里时，LLM 经常选错/遗漏工具；
 * 把这些多步组合打包成<strong>粗粒度能力</strong>暴露给主 Agent，决策空间由 N 收敛到 1。
 * <p>
 * 从调用者（主 Agent）视角看，能力与 Tool 一样：都有 name/description/parameters/execute。
 * 差异发生在 Executor：AiTool 走 HTTP/Java 直接执行；本接口的实现会被 Executor 进一步展开为
 * 子 ReAct 循环 / 固定 Workflow / 装饰 Tool。
 * <p>
 * 检索（ToolRetrieval）层统一把 AiTool 与 AiSkill 嵌入到同一个 Milvus collection，
 * 靠 scalar 字段 {@code kind} 区分形态（含 legacy 值 {@code SKILL}）。
 */
public interface AiSkill extends AiTool {

    /** 能力形态：{@link SkillKind#SUB_AGENT}、{@link SkillKind#INTERACTIVE_FORM} 等（类型名 legacy）。 */
    SkillKind kind();

    /** 能力元数据（版本 / 副作用 / HITL / 超时）。 */
    SkillMetadata metadata();

    /**
     * 该能力内部可能调用的 Tool 名称白名单（主要供 SubAgent 形态使用）。
     * 用于：
     * <ol>
     *   <li>子 Agent 构建时只注入这些 Tool（收敛决策空间）；</li>
     *   <li>依赖分析 / 影响面分析（Tool 下线时哪些能力会受影响）。</li>
     * </ol>
     */
    default List<String> dependsOnTools() {
        return Collections.emptyList();
    }
}
