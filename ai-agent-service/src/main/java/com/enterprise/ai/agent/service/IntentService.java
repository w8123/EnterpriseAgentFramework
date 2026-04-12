package com.enterprise.ai.agent.service;

import com.enterprise.ai.agent.agent.AgentDefinition;
import com.enterprise.ai.agent.agent.AgentDefinitionService;
import com.enterprise.ai.agent.llm.LlmService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 意图识别服务
 * <p>
 * 通过 LlmService（Spring AI 路径）分析用户输入，将其归类为已注册的意图类型。
 * <p>
 * 核心改进：意图候选列表从 {@link AgentDefinitionService} 动态读取，
 * 新增领域 Agent 后意图识别自动生效，无需修改代码。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IntentService {

    private final LlmService llmService;
    private final AgentDefinitionService agentDefinitionService;

    private static final String FALLBACK_INTENT = "GENERAL_CHAT";

    /**
     * 识别用户消息的意图
     */
    public String recognizeIntent(String userMessage) {
        log.debug("开始意图识别: {}", userMessage);

        try {
            String systemPrompt = buildIntentPrompt();
            String result = llmService.chat(systemPrompt, userMessage);

            String intent = normalizeIntent(result);
            log.info("意图识别结果: {} -> {}", userMessage, intent);
            return intent;

        } catch (Exception e) {
            log.warn("意图识别失败，使用默认意图: {}", FALLBACK_INTENT, e);
            return FALLBACK_INTENT;
        }
    }

    /**
     * 从 AgentDefinitionService 动态生成意图识别 prompt
     * <p>
     * 只列出已启用的 AgentDefinition 的 intentType 和 description，
     * 新增领域 Agent 后自动出现在候选列表中。
     */
    private String buildIntentPrompt() {
        List<AgentDefinition> enabledAgents = agentDefinitionService.list().stream()
                .filter(AgentDefinition::isEnabled)
                .filter(d -> d.getIntentType() != null && !d.getIntentType().isBlank())
                .toList();

        StringBuilder sb = new StringBuilder();
        sb.append("你是一个意图识别专家。请分析用户的输入，判断用户意图属于以下哪个类别：\n\n");

        int idx = 1;
        for (AgentDefinition def : enabledAgents) {
            sb.append(idx++).append(". ").append(def.getIntentType());
            if (def.getDescription() != null && !def.getDescription().isBlank()) {
                sb.append(" - ").append(def.getDescription());
            }
            sb.append("\n");
        }

        sb.append("\n请只返回意图类别名称（如 KNOWLEDGE_QA），不要返回其他内容。");
        return sb.toString();
    }

    /**
     * 规范化 LLM 返回的意图文本，仅允许返回已启用的意图类型
     */
    private String normalizeIntent(String rawIntent) {
        if (rawIntent == null || rawIntent.isBlank()) {
            return FALLBACK_INTENT;
        }

        String trimmed = rawIntent.trim().toUpperCase();

        List<String> enabledIntents = agentDefinitionService.list().stream()
                .filter(AgentDefinition::isEnabled)
                .map(AgentDefinition::getIntentType)
                .filter(it -> it != null && !it.isBlank())
                .toList();

        for (String intent : enabledIntents) {
            if (trimmed.contains(intent)) {
                return intent;
            }
        }

        return FALLBACK_INTENT;
    }
}
