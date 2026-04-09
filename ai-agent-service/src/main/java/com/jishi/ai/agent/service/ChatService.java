package com.jishi.ai.agent.service;

import com.jishi.ai.agent.llm.LlmService;
import com.jishi.ai.agent.model.ChatRequest;
import com.jishi.ai.agent.model.ChatResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 对话服务 — 处理简单对话场景
 * <p>
 * 不需要 Tool 调用的轻量对话，直接通过 LlmService 完成。
 * 需要 Tool 调用的复杂场景应走 AgentService（AgentScope 编排路径）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatService {

    private final LlmService llmService;

    /**
     * 处理普通对话请求（纯 LLM 对话，无 Tool Calling）
     */
    public ChatResponse chat(ChatRequest request) {
        log.info("处理对话请求: userId={}, sessionId={}", request.getUserId(), request.getSessionId());

        try {
            String answer = llmService.chat(request.getMessage());
            return ChatResponse.builder()
                    .answer(answer)
                    .build();

        } catch (Exception e) {
            log.error("对话处理失败", e);
            return ChatResponse.error(e.getMessage());
        }
    }
}
