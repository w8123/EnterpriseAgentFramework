package com.jishi.ai.agent.controller;

import com.jishi.ai.agent.model.ChatRequest;
import com.jishi.ai.agent.model.ChatResponse;
import com.jishi.ai.agent.service.ChatService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 对话接口 — 轻量级多轮对话入口
 * <p>
 * 支持同步和 SSE 流式两种响应模式，共享会话上下文记忆（Redis）。
 * 客户端首次请求可不带 sessionId，服务端自动生成并在响应中返回。
 * <p>
 * 完整 Agent 编排场景走 /api/agent/execute。
 */
@Slf4j
@RestController
@RequestMapping("/api/chat")
@RequiredArgsConstructor
public class ChatController {

    private final ChatService chatService;

    /**
     * 同步对话（支持轻量 Tool Calling）
     */
    @PostMapping
    public ResponseEntity<ChatResponse> chat(@Valid @RequestBody ChatRequest request) {
        log.info("收到对话请求: userId={}", request.getUserId());
        ChatResponse response = chatService.chat(request);
        return ResponseEntity.ok(response);
    }

    /**
     * 流式对话（SSE）— 逐 token 推送
     * <p>
     * 事件类型：
     * - message：文本片段
     * - done：流结束，data 为 sessionId
     * - error：流出错
     */
    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chatStream(@Valid @RequestBody ChatRequest request) {
        log.info("收到流式对话请求: userId={}", request.getUserId());
        return chatService.chatStream(request);
    }

    @DeleteMapping("/session/{sessionId}")
    public ResponseEntity<Void> clearSession(@PathVariable String sessionId) {
        log.info("清除会话记忆: sessionId={}", sessionId);
        chatService.clearSession(sessionId);
        return ResponseEntity.noContent().build();
    }
}
