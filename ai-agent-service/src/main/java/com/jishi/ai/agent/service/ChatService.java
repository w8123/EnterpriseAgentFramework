package com.jishi.ai.agent.service;

import com.jishi.ai.agent.llm.LlmService;
import com.jishi.ai.agent.memory.ConversationMemoryService;
import com.jishi.ai.agent.memory.MemoryMessage;
import com.jishi.ai.agent.model.ChatRequest;
import com.jishi.ai.agent.model.ChatResponse;
import com.jishi.ai.agent.service.LightweightToolCaller.ToolCallResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Flux;

import java.util.*;

/**
 * 对话服务 — 轻量级多轮对话 + 轻量 Tool Calling
 * <p>
 * 支持会话上下文记忆（Redis）和轻量工具调用（知识搜索等）。
 * 完整 Agent 编排（多步推理、复杂工具组合）走 AgentService。
 * <p>
 * 轻量 Tool Calling 流程：
 * 1. 将用户消息 + 工具描述发送给 LLM
 * 2. 若 LLM 选择调用工具 → 执行工具 → 将结果作为上下文再次调用 LLM 生成最终回答
 * 3. 若 LLM 直接回答 → 返回
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatService {

    private final LlmService llmService;
    private final ConversationMemoryService memoryService;
    private final LightweightToolCaller toolCaller;

    /**
     * 处理对话请求 — 带会话记忆 + 轻量 Tool Calling
     */
    public ChatResponse chat(ChatRequest request) {
        String sessionId = resolveSessionId(request);
        log.info("处理对话请求: userId={}, sessionId={}", request.getUserId(), sessionId);

        try {
            List<MemoryMessage> history = memoryService.getHistory(sessionId);
            String systemPrompt = LlmService.DEFAULT_SYSTEM_PROMPT + toolCaller.buildToolDescriptions();

            String firstResponse = llmService.chatWithHistory(systemPrompt, history, request.getMessage());

            Optional<ToolCallResult> toolResult = toolCaller.parseAndExecute(firstResponse);
            List<String> toolCalls = new ArrayList<>();
            String answer;

            if (toolResult.isPresent()) {
                ToolCallResult tc = toolResult.get();
                toolCalls.add(tc.toolName());
                log.info("轻量 Tool Calling: tool={}, sessionId={}", tc.toolName(), sessionId);

                String contextPrompt = "你之前调用了工具 " + tc.toolName() + "，工具返回了以下信息：\n\n"
                        + tc.result() + "\n\n请根据以上信息回答用户的问题。";
                answer = llmService.chatWithHistory(
                        LlmService.DEFAULT_SYSTEM_PROMPT, history,
                        contextPrompt + "\n\n用户原始问题：" + request.getMessage());
            } else {
                answer = firstResponse;
            }

            memoryService.append(sessionId, request.getMessage(), answer);

            return ChatResponse.builder()
                    .sessionId(sessionId)
                    .answer(answer)
                    .toolCalls(toolCalls.isEmpty() ? null : toolCalls)
                    .build();

        } catch (Exception e) {
            log.error("对话处理失败", e);
            return ChatResponse.error(e.getMessage());
        }
    }

    /**
     * 流式对话 — 带会话记忆的 SSE 流式响应
     * <p>
     * 逐 token 推送到 SseEmitter，完成后将完整回答写入记忆。
     * 注意：流式路径暂不支持轻量 Tool Calling（工具调用走同步路径）。
     */
    public SseEmitter chatStream(ChatRequest request) {
        String sessionId = resolveSessionId(request);
        log.info("处理流式对话请求: userId={}, sessionId={}", request.getUserId(), sessionId);

        SseEmitter emitter = new SseEmitter(60_000L);
        StringBuilder fullAnswer = new StringBuilder();

        List<MemoryMessage> history = memoryService.getHistory(sessionId);
        Flux<String> stream = llmService.chatStreamWithHistory(
                LlmService.DEFAULT_SYSTEM_PROMPT, history, request.getMessage());

        stream.subscribe(
                chunk -> {
                    try {
                        fullAnswer.append(chunk);
                        emitter.send(SseEmitter.event()
                                .name("message")
                                .data(chunk));
                    } catch (Exception e) {
                        log.warn("[SSE] 发送失败: {}", e.getMessage());
                        emitter.completeWithError(e);
                    }
                },
                error -> {
                    log.error("[SSE] 流式对话异常", error);
                    try {
                        emitter.send(SseEmitter.event()
                                .name("error")
                                .data("流式对话出错: " + error.getMessage()));
                    } catch (Exception ignored) {}
                    emitter.completeWithError(error);
                },
                () -> {
                    memoryService.append(sessionId, request.getMessage(), fullAnswer.toString());
                    try {
                        emitter.send(SseEmitter.event()
                                .name("done")
                                .data(sessionId));
                        emitter.complete();
                    } catch (Exception e) {
                        log.warn("[SSE] 完成发送失败: {}", e.getMessage());
                    }
                }
        );

        emitter.onTimeout(emitter::complete);
        emitter.onError(e -> log.warn("[SSE] emitter error: {}", e.getMessage()));

        return emitter;
    }

    /**
     * 清除会话记忆
     */
    public void clearSession(String sessionId) {
        memoryService.clear(sessionId);
    }

    private String resolveSessionId(ChatRequest request) {
        if (request.getSessionId() != null && !request.getSessionId().isBlank()) {
            return request.getSessionId();
        }
        String generated = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        request.setSessionId(generated);
        return generated;
    }
}
