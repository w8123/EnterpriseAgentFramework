package com.enterprise.ai.agent.agentscope;

import com.enterprise.ai.agent.acl.ToolAclDecision;
import com.enterprise.ai.agent.acl.ToolAclService;
import com.enterprise.ai.agent.agent.AgentDefinition;
import com.enterprise.ai.agent.agentscope.adapter.AiToolAgentAdapter;
import com.enterprise.ai.agent.config.LLMConfig;
import com.enterprise.ai.agent.config.ToolRetrievalProperties;
import com.enterprise.ai.agent.skill.SubAgentSkillExecutor;
import com.enterprise.ai.agent.tool.log.ToolCallLogService;
import com.enterprise.ai.agent.tool.log.ToolExecutionContext;
import com.enterprise.ai.agent.tool.retrieval.RetrievalScope;
import com.enterprise.ai.agent.tool.retrieval.ToolCandidate;
import com.enterprise.ai.agent.tool.retrieval.ToolRetrievalService;
import com.enterprise.ai.agent.tools.ToolRegistry;
import com.enterprise.ai.agent.tools.definition.ToolDefinitionEntity;
import com.enterprise.ai.agent.tools.definition.ToolDefinitionMapper;
import com.enterprise.ai.agent.tools.definition.ToolDefinitionService;
import com.enterprise.ai.skill.AiSkill;
import com.enterprise.ai.skill.AiTool;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.model.Model;
import io.agentscope.core.tool.Toolkit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Agent 工厂 — 根据 {@link AgentDefinition} 创建 AgentScope {@link ReActAgent}。
 * <p>
 * <h3>Tool Retrieval</h3>
 * 当传入 {@code userMessage} 非空时，本工厂会：
 * <ol>
 *   <li>以 {@code definition.tools} 作白名单，先按名称解析到 {@code tool_definition.id}；</li>
 *   <li>调用 {@link ToolRetrievalService} 在向量库中召回 top-K 候选（命中项会在白名单内取交集）；</li>
 *   <li>若白名单 == null/空 → 以召回结果为最终 toolset；若白名单非空 → 取交集；</li>
 *   <li>将召回 trace 回填到 {@link ToolExecutionContext}，便于 Skill Mining 审计。</li>
 * </ol>
 * 召回异常或未启用时，自动回退到「白名单全量注入」旧行为，保证兼容性。
 */
@Slf4j
@Component
public class AgentFactory {

    private final Model singleAgentModel;
    private final Model multiAgentModel;
    private final ToolRegistry toolRegistry;
    private final ToolDefinitionService toolDefinitionService;
    private final ToolDefinitionMapper toolDefinitionMapper;
    private final ToolRetrievalService toolRetrievalService;
    private final ToolCallLogService toolCallLogService;
    private final ToolRetrievalProperties retrievalProperties;
    private final ObjectMapper objectMapper;
    private final ToolAclService toolAclService;
    private final int defaultMaxSteps;

    public AgentFactory(
            @Qualifier("agentScopeChatModel") Model singleAgentModel,
            @Qualifier("agentScopeMultiAgentModel") Model multiAgentModel,
            ToolRegistry toolRegistry,
            ToolDefinitionService toolDefinitionService,
            ToolDefinitionMapper toolDefinitionMapper,
            ToolRetrievalService toolRetrievalService,
            ToolCallLogService toolCallLogService,
            ToolRetrievalProperties retrievalProperties,
            ObjectMapper objectMapper,
            ToolAclService toolAclService,
            LLMConfig llmConfig) {
        this.singleAgentModel = singleAgentModel;
        this.multiAgentModel = multiAgentModel;
        this.toolRegistry = toolRegistry;
        this.toolDefinitionService = toolDefinitionService;
        this.toolDefinitionMapper = toolDefinitionMapper;
        this.toolRetrievalService = toolRetrievalService;
        this.toolCallLogService = toolCallLogService;
        this.retrievalProperties = retrievalProperties;
        this.objectMapper = objectMapper;
        this.toolAclService = toolAclService;
        this.defaultMaxSteps = llmConfig.getMaxSteps();
        log.info("[AgentFactory] 初始化完成: defaultMaxSteps={}, toolRetrieval={}",
                defaultMaxSteps, retrievalProperties.isEnabled());
    }

    /**
     * 旧入口：不做动态召回，仍走白名单全量注入（保持向后兼容）。
     */
    public ReActAgent buildFromDefinition(AgentDefinition definition) {
        return buildFromDefinition(definition, null, null);
    }

    /**
     * 新入口：根据 {@code userMessage} 动态召回并注入。
     *
     * @param definition  Agent 定义
     * @param userMessage 当前用户输入（用于语义召回），null 则退化为白名单行为
     * @param context     本次执行的审计上下文（traceId/sessionId/userId 已填充），
     *                    若召回成功，retrievalTraceJson 会被回填
     */
    public ReActAgent buildFromDefinition(AgentDefinition definition,
                                          String userMessage,
                                          ToolExecutionContext context) {
        Model model = definition.isUseMultiAgentModel() ? multiAgentModel : singleAgentModel;
        int maxSteps = definition.getMaxSteps() > 0 ? definition.getMaxSteps() : defaultMaxSteps;

        List<String> whitelist = definition.getTools();
        List<String> finalTools = resolveToolNames(whitelist, userMessage, context);

        var builder = ReActAgent.builder()
                .name(definition.getName())
                .sysPrompt(definition.getSystemPrompt())
                .model(model)
                .maxIters(maxSteps);

        if (finalTools != null && !finalTools.isEmpty()) {
            builder.toolkit(createToolkit(finalTools, context));
        }

        log.debug("[AgentFactory] 构建 Agent: name={}, tools={}, model={}, maxSteps={}",
                definition.getName(),
                finalTools == null || finalTools.isEmpty() ? "none" : finalTools,
                definition.isUseMultiAgentModel() ? "multi-agent" : "single-agent",
                maxSteps);

        return builder.build();
    }

    /**
     * 结合召回结果 & 白名单产生最终 toolNames。
     * <p>
     * - 未启用召回 / userMessage 为空 / 召回异常 → 返回白名单（旧行为）；<br>
     * - 白名单为空且召回非空 → 返回召回结果；<br>
     * - 白名单非空 → 返回白名单 ∩ 召回（无交集时降级为白名单）。
     */
    List<String> resolveToolNames(List<String> whitelist, String userMessage, ToolExecutionContext context) {
        boolean retrievalEligible = retrievalProperties.isEnabled()
                && userMessage != null && !userMessage.isBlank();
        if (!retrievalEligible) {
            return whitelist;
        }

        List<Long> whitelistIds = resolveWhitelistIds(whitelist);
        // 白名单显式为空列表 → 明确是 "不要挂工具"
        if (whitelist != null && whitelist.isEmpty()) {
            return List.of();
        }

        RetrievalScope scope = new RetrievalScope(
                null, null,
                whitelistIds == null || whitelistIds.isEmpty() ? null : whitelistIds,
                true, true);
        List<ToolCandidate> candidates;
        try {
            candidates = toolRetrievalService.retrieve(userMessage, scope, retrievalProperties.getTopK(), context);
        } catch (Exception ex) {
            log.warn("[AgentFactory] Tool 召回异常，降级到白名单: {}", ex.toString());
            if (retrievalProperties.isFallbackOnError()) {
                return whitelist;
            }
            return whitelist;
        }

        fillRetrievalTrace(context, candidates);

        if (candidates == null || candidates.isEmpty()) {
            return whitelist;
        }

        List<String> retrievedNames = candidates.stream()
                .map(ToolCandidate::toolName)
                .filter(name -> name != null && !name.isBlank())
                .distinct()
                .collect(Collectors.toCollection(ArrayList::new));

        if (whitelist == null) {
            return retrievedNames;
        }

        LinkedHashSet<String> whitelistSet = new LinkedHashSet<>(whitelist);
        List<String> intersection = retrievedNames.stream()
                .filter(whitelistSet::contains)
                .collect(Collectors.toCollection(ArrayList::new));
        if (intersection.isEmpty()) {
            log.debug("[AgentFactory] 召回与白名单无交集，回退白名单全量: whitelist={}, retrieved={}",
                    whitelist, retrievedNames);
            return whitelist;
        }
        return intersection;
    }

    /**
     * 将 tool 名解析成 {@code tool_definition.id}；找不到的名字跳过（比如 search_knowledge 这类 code tool
     * 同样有记录，可以查到 id）。
     */
    List<Long> resolveWhitelistIds(List<String> whitelist) {
        if (whitelist == null || whitelist.isEmpty()) {
            return null;
        }
        List<Long> ids = new ArrayList<>();
        for (String name : whitelist) {
            toolDefinitionService.findByName(name)
                    .map(ToolDefinitionEntity::getId)
                    .ifPresent(ids::add);
        }
        return ids;
    }

    private void fillRetrievalTrace(ToolExecutionContext context, List<ToolCandidate> candidates) {
        if (context == null || candidates == null || candidates.isEmpty()) {
            return;
        }
        try {
            List<Map<String, Object>> trace = new ArrayList<>();
            for (ToolCandidate c : candidates) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("toolId", c.toolId());
                row.put("toolName", c.toolName());
                row.put("score", c.score());
                trace.add(row);
            }
            context.setRetrievalTraceJson(objectMapper.writeValueAsString(trace));
        } catch (Exception ex) {
            log.debug("[AgentFactory] 写入 retrievalTrace 失败（忽略）: {}", ex.toString());
        }
    }

    /**
     * 创建 Toolkit 并注册 {@link AiToolAgentAdapter}：按白名单顺序过滤已启用可见的 tool 或 skill。
     * <p>
     * Phase 2.0 起，TOOL 与 SKILL 对 Adapter 无感知差异：SubAgentSkill 自身的 execute() 会委托给
     * {@link SubAgentSkillExecutor} 展开为子 ReActAgent。区别仅在日志标签：TOOL vs SKILL。
     * <p>
     * 防御递归：当 {@code SubAgentSkillExecutor.currentDepth() > 0} 说明正在为某个 SubAgent 的
     * 子 Toolkit 装配工具，此时直接拒绝再挂 Skill，避免多层嵌套（仅保留一层 SubAgent 深度）。
     */
    Toolkit createToolkit(List<String> toolNames, ToolExecutionContext context) {
        Toolkit toolkit = new Toolkit();
        boolean insideChildSkill = SubAgentSkillExecutor.currentDepth() > 0;
        List<String> roles = context == null ? null : context.getRoles();
        for (String toolName : toolNames) {
            if (!toolDefinitionService.isAgentCallable(toolName)) {
                log.warn("[AgentFactory] 工具/Skill 未启用或不可见，跳过注册: {}", toolName);
                continue;
            }
            if (!toolRegistry.contains(toolName)) {
                log.warn("[AgentFactory] ToolRegistry 中未找到工具/Skill: {}", toolName);
                continue;
            }
            AiTool aiTool = toolRegistry.get(toolName);
            boolean isSkill = aiTool instanceof AiSkill;
            if (isSkill && insideChildSkill) {
                log.warn("[AgentFactory] 子 Skill 内部不允许嵌套 Skill，跳过: skill={}", toolName);
                continue;
            }
            // Phase 3.1 Tool ACL：在装配阶段按 roles × tool_acl 过滤，被拦的能力完全不进 LLM 视野
            ToolAclDecision decision = toolAclService.decide(roles, toolName, isSkill);
            if (decision == ToolAclDecision.SKIPPED) {
                // 兼容灰度期：roles 为空时只打 warn，不拦截
                log.warn("[AgentFactory][ACL] roles 为空，跳过 ACL 校验（建议网关补上）: tool={}", toolName);
            } else if (decision.isDenied()) {
                log.warn("[AgentFactory][ACL] 拦截装配: kind={}, name={}, roles={}, reason={}",
                        isSkill ? "SKILL" : "TOOL", toolName, roles, decision);
                continue;
            }
            // Phase 3.0 sideEffect 运行时闸口：把 tool_definition.side_effect 透传给 Adapter
            String sideEffect = toolDefinitionService.findByName(toolName)
                    .map(ToolDefinitionEntity::getSideEffect)
                    .orElse(null);
            toolkit.registerAgentTool(new AiToolAgentAdapter(aiTool, context, toolCallLogService, sideEffect));
            log.debug("[AgentFactory] 装配 {}: {} (sideEffect={}, aclDecision={})",
                    isSkill ? "SKILL" : "TOOL", toolName, sideEffect, decision);
        }
        return toolkit;
    }

    // 保留旧方法签名以免破坏其他测试
    Toolkit createToolkit(List<String> toolNames) {
        return createToolkit(toolNames, null);
    }

    /**
     * 给 AgentRouter 使用的只读访问：返回白名单解析后的 IDs（便于手工 retrieval 测试）。
     */
    public List<Long> publicResolveWhitelistIds(List<String> whitelist) {
        List<Long> ids = resolveWhitelistIds(whitelist);
        return ids == null ? Collections.emptyList() : ids;
    }

    /**
     * 直接读 {@code tool_definition} 做回显，方便 ToolRetrievalController 展示结果时补充详情。
     */
    public ToolDefinitionEntity lookupTool(Long id) {
        return id == null ? null : toolDefinitionMapper.selectById(id);
    }
}
