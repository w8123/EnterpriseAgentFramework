package com.jishi.ai.agent.controller;

import com.jishi.ai.agent.model.ChatRequest;
import com.jishi.ai.agent.model.ChatResponse;
import com.jishi.ai.agent.service.ChatService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 对话接口 — 轻量级对话场景入口
 * <p>
 * 适用于不需要完整Agent编排的简单对话，
 * 自动启用Tool Calling让LLM按需调用工具。
 */
@Slf4j
@RestController
@RequestMapping("/api/chat")
@RequiredArgsConstructor
public class ChatController {

    private final ChatService chatService;

    @PostMapping
    public ResponseEntity<ChatResponse> chat(@Valid @RequestBody ChatRequest request) {
        log.info("收到对话请求: userId={}", request.getUserId());
        ChatResponse response = chatService.chat(request);
        return ResponseEntity.ok(response);
    }
}
