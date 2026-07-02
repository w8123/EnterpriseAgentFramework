package com.enterprise.ai.model.controller;

import com.enterprise.ai.common.dto.ApiResult;
import com.enterprise.ai.model.service.*;
import com.enterprise.ai.model.util.ChatDebugLogs;
import com.enterprise.ai.model.util.EmbeddingDebugLogs;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

@Slf4j
@RestController
@RequestMapping("/model")
@RequiredArgsConstructor
public class ModelController {

    private final ModelRoutingService routingService;

    @PostMapping("/chat")
    public ApiResult<ChatResponse> chat(@RequestBody ChatRequest request) {
        log.info("[Chat] received modelInstanceId={}, messages={}",
                request.getModelInstanceId(),
                request.getMessages() == null ? 0 : request.getMessages().size());
        ChatDebugLogs.logChatRequest(log, "[Chat] request", request);
        return ApiResult.ok(routingService.chat(request));
    }

    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> chatStream(@RequestBody ChatRequest request) {
        log.info("[ChatStream] received modelInstanceId={}, messages={}",
                request.getModelInstanceId(),
                request.getMessages() == null ? 0 : request.getMessages().size());
        ChatDebugLogs.logChatRequest(log, "[ChatStream] request", request);
        return routingService.chatStream(request)
                .doOnComplete(() -> log.info("[ChatStream] completed modelInstanceId={}", request.getModelInstanceId()))
                .doOnError(e -> log.error("[ChatStream] failed modelInstanceId={}", request.getModelInstanceId(), e));
    }

    @PostMapping("/embedding")
    public ApiResult<EmbeddingResponse> embed(@RequestBody EmbeddingRequest request) {
        int textCount = request.getTexts() == null ? 0 : request.getTexts().size();
        log.info("[Embedding] received modelInstanceId={}, texts={}", request.getModelInstanceId(), textCount);
        EmbeddingDebugLogs.logInputTexts(log, "[Embedding] request", request.getTexts());
        return ApiResult.ok(routingService.embed(request));
    }

    @PostMapping("/rerank")
    public ApiResult<RerankResponse> rerank(@RequestBody RerankRequest request) {
        return ApiResult.ok(routingService.rerank(request));
    }
}
