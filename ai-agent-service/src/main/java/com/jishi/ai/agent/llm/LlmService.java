package com.jishi.ai.agent.llm;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

/**
 * LLM 统一调用服务 — Spring AI 的唯一出口
 * <p>
 * 封装 Spring AI ChatClient，向上层提供简洁的 LLM 调用接口。
 * Agent / Service / Workflow 统一通过本类调用大模型，不直接依赖 Spring AI API。
 * <p>
 * 职责边界：
 * - 本类只做"发 Prompt、拿回答"
 * - Tool 调用决策由 AgentScope Agent 层负责
 * - Prompt 工程由调用方负责
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LlmService {

    private final ChatClient chatClient;

    /**
     * 基础对话（使用默认 system prompt）
     */
    public String chat(String userMessage) {
        log.debug("[LlmService] chat: {}", userMessage);
        return chatClient.prompt()
                .user(userMessage)
                .call()
                .content();
    }

    /**
     * 带 system prompt 的对话
     */
    public String chat(String systemPrompt, String userMessage) {
        log.debug("[LlmService] chat with system prompt");
        return chatClient.prompt()
                .system(systemPrompt)
                .user(userMessage)
                .call()
                .content();
    }

    /**
     * 自由 prompt 对话（system + user 拼接场景）
     */
    public String chatWithContext(String systemPrompt, String context, String userMessage) {
        log.debug("[LlmService] chat with context");
        String combined = "背景信息：\n" + context + "\n\n用户问题：\n" + userMessage;
        return chatClient.prompt()
                .system(systemPrompt)
                .user(combined)
                .call()
                .content();
    }
}
