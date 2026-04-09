package com.enterprise.ai.model.controller;

import com.enterprise.ai.common.dto.ApiResult;
import com.enterprise.ai.model.service.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

import java.util.List;

@RestController
@RequestMapping("/model")
@RequiredArgsConstructor
public class ModelController {

    private final ModelRoutingService routingService;

    /** 同步对话 */
    @PostMapping("/chat")
    public ApiResult<ChatResponse> chat(@RequestBody ChatRequest request) {
        return ApiResult.ok(routingService.chat(request));
    }

    /** 流式对话（SSE） */
    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> chatStream(@RequestBody ChatRequest request) {
        return routingService.chatStream(request);
    }

    /** 文本向量化 */
    @PostMapping("/embedding")
    public ApiResult<EmbeddingResponse> embed(@RequestBody EmbeddingRequest request) {
        return ApiResult.ok(routingService.embed(request));
    }

    /** 可用 Provider 列表 */
    @GetMapping("/providers")
    public ApiResult<List<ModelRoutingService.ProviderInfo>> listProviders() {
        return ApiResult.ok(routingService.listProviders());
    }

    /** Provider 连通性测试 */
    @PostMapping("/providers/{name}/test")
    public ApiResult<Boolean> testProvider(@PathVariable String name) {
        return ApiResult.ok(routingService.testProvider(name));
    }
}
