package com.jishi.ai.agent.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.Map;

/**
 * ai-model-service Feign Client — 用于直接调用统一模型网关。
 */
@FeignClient(
        name = "ai-model-service",
        url = "${services.model-service.url:http://localhost:8090}",
        path = "/model"
)
public interface ModelServiceClient {

    @PostMapping("/chat")
    Map<String, Object> chat(@RequestBody Map<String, Object> request);

    @PostMapping("/embedding")
    Map<String, Object> embed(@RequestBody Map<String, Object> request);
}
