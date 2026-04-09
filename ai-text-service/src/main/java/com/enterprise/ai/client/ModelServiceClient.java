package com.enterprise.ai.client;

import com.enterprise.ai.common.dto.ApiResult;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.List;
import java.util.Map;

/**
 * ai-model-service Feign Client。
 * Nacos 启用时按服务名路由，否则使用 url 属性直连。
 */
@FeignClient(
        name = "ai-model-service",
        url = "${embedding.model-service.url:}",
        path = "/model"
)
public interface ModelServiceClient {

    @PostMapping("/chat")
    ApiResult<Map<String, Object>> chat(@RequestBody Map<String, Object> request);

    @PostMapping("/embedding")
    ApiResult<EmbeddingResult> embed(@RequestBody EmbeddingParam request);

    record EmbeddingParam(String provider, String model, List<String> texts) {}

    record EmbeddingResult(String model, String provider, int dimension, List<List<Float>> embeddings) {}
}
