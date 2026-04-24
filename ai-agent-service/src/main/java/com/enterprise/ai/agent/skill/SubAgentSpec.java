package com.enterprise.ai.agent.skill;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * SubAgentSkill 的专属 spec，持久化到 {@code tool_definition.spec_json}。
 * <p>
 * Phase 2.0 不允许 spec 再含 pipelineAgentIds 之类的递归 Skill 引用，
 * 避免在 ReAct 循环深处触发不可控的跨 Skill 调用链。
 *
 * @param systemPrompt    子 Agent 的 system prompt（核心）
 * @param toolWhitelist   子 Agent 允许用的 Tool 名白名单（Skill 不能嵌套 Skill 调用）
 * @param llmProvider     可选，覆盖默认 LLM provider；为空表示走主 Agent 同款模型
 * @param llmModel        可选，覆盖默认模型名；为空表示走主 Agent 同款模型
 * @param maxSteps        子 Agent 内部 ReAct 最大步数；0 表示走全局 agent.max-steps
 * @param useMultiAgentModel 子 Agent 是否使用 multi-agent model（pipeline-friendly 一般 true）
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SubAgentSpec(
        String systemPrompt,
        List<String> toolWhitelist,
        String llmProvider,
        String llmModel,
        int maxSteps,
        boolean useMultiAgentModel
) {

    public SubAgentSpec {
        toolWhitelist = toolWhitelist == null ? List.of() : List.copyOf(toolWhitelist);
    }
}
