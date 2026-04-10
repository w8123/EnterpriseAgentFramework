package com.enterprise.ai.model.controller;

import com.enterprise.ai.common.dto.ApiResult;
import com.enterprise.ai.model.service.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

import java.util.List;

@Slf4j
@RestController
@RequestMapping("/model")
@RequiredArgsConstructor
public class ModelController {

    private final ModelRoutingService routingService;

    /** 同步对话 */
    @PostMapping("/chat")
    public ApiResult<ChatResponse> chat(@RequestBody ChatRequest request) {
        log.info("[Chat] 收到请求 provider={}, model={}, messages数量={}",
                request.getProvider(), request.getModel(),
                request.getMessages() == null ? 0 : request.getMessages().size());
        ChatResponse response = routingService.chat(request);
        log.info("[Chat] 响应完成 provider={}, model={}, tokens={}",
                response.getProvider(), response.getModel(),
                response.getUsage() != null ? response.getUsage().getTotalTokens() : "N/A");
        return ApiResult.ok(response);
    }

    /** 流式对话（SSE） */
    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> chatStream(@RequestBody ChatRequest request) {
        log.info("[ChatStream] 收到流式请求 provider={}, model={}, messages数量={}",
                request.getProvider(), request.getModel(),
                request.getMessages() == null ? 0 : request.getMessages().size());
        return routingService.chatStream(request)
                .doOnComplete(() -> log.info("[ChatStream] 流式响应结束 provider={}, model={}",
                        request.getProvider(), request.getModel()))
                .doOnError(e -> log.error("[ChatStream] 流式响应异常 provider={}, model={}",
                        request.getProvider(), request.getModel(), e));
    }

    /** 文本向量化 */
    @PostMapping("/embedding")
    public ApiResult<EmbeddingResponse> embed(@RequestBody EmbeddingRequest request) {
        int textCount = request.getTexts() == null ? 0 : request.getTexts().size();
        log.info("[Embedding] 收到请求 provider={}, model={}, 文本数量={}",
                request.getProvider(), request.getModel(), textCount);
        EmbeddingResponse response = routingService.embed(request);
        log.info("[Embedding] 响应完成 provider={}, model={}, 向量数量={}, 维度={}",
                response.getProvider(), response.getModel(),
                response.getEmbeddings() == null ? 0 : response.getEmbeddings().size(),
                response.getDimension());
        return ApiResult.ok(response);
    }

    /** 可用 Provider 列表 */
    @GetMapping("/providers")
    public ApiResult<List<ModelRoutingService.ProviderInfo>> listProviders() {
        return ApiResult.ok(routingService.listProviders());
    }

    /** Provider 连通性测试 */
    @PostMapping("/providers/{name}/test")
    public ApiResult<Boolean> testProvider(@PathVariable String name) {
        log.info("[ProviderTest] 测试 provider={}", name);
        boolean result = routingService.testProvider(name);
        log.info("[ProviderTest] provider={} 测试结果={}", name, result);
        return ApiResult.ok(result);
    }
}
