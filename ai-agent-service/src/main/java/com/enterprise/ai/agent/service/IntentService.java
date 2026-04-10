package com.enterprise.ai.agent.service;

import com.enterprise.ai.agent.config.LLMConfig;
import com.enterprise.ai.agent.config.LLMConfig.AgentsToggle;
import com.enterprise.ai.agent.llm.LlmService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 意图识别服务
 * <p>
 * 通过 LlmService（Spring AI 路径）分析用户输入，将其归类为预定义的意图类型：
 * QUERY_DATA / KNOWLEDGE_QA / BUSINESS_OPERATION / ANALYSIS / GENERAL_CHAT
 * <p>
 * 系统 prompt 根据 agent.agents.* 开关动态生成——
 * 关闭的 Agent 类型不会出现在候选列表中。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IntentService {

    private final LlmService llmService;
    private final LLMConfig llmConfig;

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
     * 根据 Agent 开关动态生成意图识别 prompt
     */
    private String buildIntentPrompt() {
        AgentsToggle toggle = llmConfig.getAgents();
        StringBuilder sb = new StringBuilder();
        sb.append("你是一个意图识别专家。请分析用户的输入，判断用户意图属于以下哪个类别：\n");

        int idx = 1;
        if (toggle.isQueryData()) {
            sb.append(idx++).append(". QUERY_DATA - 问数/数据查询（如：查询某个数据、统计某项指标）\n");
        }
        if (toggle.isKnowledgeQa()) {
            sb.append(idx++).append(". KNOWLEDGE_QA - 知识问答（如：查询制度规定、操作流程、技术规范）\n");
        }
        if (toggle.isBusinessOperation()) {
            sb.append(idx++).append(". BUSINESS_OPERATION - 业务操作（如：发起审批、提交工单、执行业务动作）\n");
        }
        if (toggle.isAnalysis()) {
            sb.append(idx++).append(". ANALYSIS - 数据分析（如：对比分析、趋势分析、综合评估、给出建议）\n");
        }
        if (toggle.isCreativeTask()) {
            sb.append(idx++).append(". CREATIVE_TASK - 创意任务（如：起名字、写文案、写邮件、生成方案等需要先查询用户信息或业务数据再进行创作的任务）\n");
        }
        if (toggle.isGeneralChat()) {
            sb.append(idx).append(". GENERAL_CHAT - 闲聊（不属于以上类别的一般对话）\n");
        }

        sb.append("请只返回意图类别名称，不要返回其他内容。");
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
        AgentsToggle toggle = llmConfig.getAgents();

        if (toggle.isQueryData() && trimmed.contains("QUERY_DATA")) return "QUERY_DATA";
        if (toggle.isKnowledgeQa() && trimmed.contains("KNOWLEDGE_QA")) return "KNOWLEDGE_QA";
        if (toggle.isBusinessOperation() && trimmed.contains("BUSINESS_OPERATION")) return "BUSINESS_OPERATION";
        if (toggle.isAnalysis() && trimmed.contains("ANALYSIS")) return "ANALYSIS";
        if (toggle.isCreativeTask() && trimmed.contains("CREATIVE_TASK")) return "CREATIVE_TASK";
        if (trimmed.contains("GENERAL_CHAT")) return "GENERAL_CHAT";

        return FALLBACK_INTENT;
    }
}
