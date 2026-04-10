package com.enterprise.ai.agent.llm;

import com.enterprise.ai.agent.client.ModelServiceClient;
import com.enterprise.ai.agent.client.ModelServiceClient.*;
import com.enterprise.ai.agent.client.ModelStreamClient;
import com.enterprise.ai.agent.memory.MemoryMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;

/**
 * LLM 统一调用服务 — 通过 ai-model-service 网关调用大模型
 * <p>
 * 所有非 Agent 路径的 LLM 调用通过此类，底层走 Feign 调 ai-model-service。
 * Agent 路径由 AgentScope 通过 OpenAIChatModel → model-service 代理端点调用。
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

    private final ModelServiceClient modelServiceClient;
    private final ModelStreamClient modelStreamClient;

    @Value("${agentscope.model.name:qwen-max}")
    private String defaultModel;

    public static final String DEFAULT_SYSTEM_PROMPT =
            "你是青岛地铁的企业级智能助手，名叫小铁宝。你能够帮助用户查询数据、搜索知识库、执行业务操作。请用专业且友好的语气回答问题。";

    /**
     * 基础对话（无上下文）
     */
    public String chat(String userMessage) {
        log.debug("[LlmService] chat: {}", userMessage);
        return callModelService(DEFAULT_SYSTEM_PROMPT, userMessage, null);
    }

    /**
     * 带 system prompt 的对话（无上下文）
     */
    public String chat(String systemPrompt, String userMessage) {
        log.debug("[LlmService] chat with system prompt");
        return callModelService(systemPrompt, userMessage, null);
    }

    /**
     * 带会话历史的多轮对话
     *
     * @param systemPrompt 系统提示
     * @param history      历史消息列表（从 ConversationMemoryService 获取）
     * @param userMessage  当前用户消息
     */
    public String chatWithHistory(String systemPrompt, List<MemoryMessage> history, String userMessage) {
        log.debug("[LlmService] chat with history, historySize={}", history.size());
        return callModelService(systemPrompt, userMessage, history);
    }

    /**
     * 自由 prompt 对话（system + 背景信息 + user 拼接场景）
     */
    public String chatWithContext(String systemPrompt, String context, String userMessage) {
        log.debug("[LlmService] chat with context");
        String combined = "背景信息：\n" + context + "\n\n用户问题：\n" + userMessage;
        return callModelService(systemPrompt, combined, null);
    }

    private String callModelService(String systemPrompt, String userMessage, List<MemoryMessage> history) {
        List<ModelChatRequest.ChatMessage> messages = new ArrayList<>();

        if (systemPrompt != null && !systemPrompt.isBlank()) {
            messages.add(ModelChatRequest.ChatMessage.builder()
                    .role("system").content(systemPrompt).build());
        }

        if (history != null) {
            for (MemoryMessage msg : history) {
                messages.add(ModelChatRequest.ChatMessage.builder()
                        .role(msg.getRole()).content(msg.getContent()).build());
            }
        }

        messages.add(ModelChatRequest.ChatMessage.builder()
                .role("user").content(userMessage).build());

        ModelChatRequest request = ModelChatRequest.builder()
                .model(defaultModel)
                .messages(messages)
                .build();

        try {
            ModelChatResult result = modelServiceClient.chat(request);
            if (result.getData() != null && result.getData().getContent() != null) {
                return result.getData().getContent();
            }
            log.warn("[LlmService] model-service 返回空内容: code={}, msg={}",
                    result.getCode(), result.getMessage());
            return "模型服务返回为空";
        } catch (Exception e) {
            log.error("[LlmService] model-service 调用失败", e);
            throw new RuntimeException("LLM 调用失败: " + e.getMessage(), e);
        }
    }

    // ==================== 流式调用 ====================

    /**
     * 流式对话 — 带会话历史
     *
     * @return 逐 token 文本片段的 Flux
     */
    public Flux<String> chatStreamWithHistory(String systemPrompt, List<MemoryMessage> history, String userMessage) {
        log.debug("[LlmService] stream chat with history, historySize={}", history.size());
        ModelChatRequest request = buildRequest(systemPrompt, history, userMessage);
        return modelStreamClient.chatStream(request);
    }

    /**
     * 流式基础对话（无上下文）
     */
    public Flux<String> chatStream(String userMessage) {
        ModelChatRequest request = buildRequest(DEFAULT_SYSTEM_PROMPT, null, userMessage);
        return modelStreamClient.chatStream(request);
    }

    private ModelChatRequest buildRequest(String systemPrompt, List<MemoryMessage> history, String userMessage) {
        List<ModelChatRequest.ChatMessage> messages = new ArrayList<>();

        if (systemPrompt != null && !systemPrompt.isBlank()) {
            messages.add(ModelChatRequest.ChatMessage.builder()
                    .role("system").content(systemPrompt).build());
        }
        if (history != null) {
            for (MemoryMessage msg : history) {
                messages.add(ModelChatRequest.ChatMessage.builder()
                        .role(msg.getRole()).content(msg.getContent()).build());
            }
        }
        messages.add(ModelChatRequest.ChatMessage.builder()
                .role("user").content(userMessage).build());

        return ModelChatRequest.builder()
                .model(defaultModel)
                .messages(messages)
                .build();
    }
}
