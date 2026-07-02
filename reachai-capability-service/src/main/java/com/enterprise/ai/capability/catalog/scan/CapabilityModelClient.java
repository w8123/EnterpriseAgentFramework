package com.enterprise.ai.capability.catalog.scan;

import com.enterprise.ai.common.dto.ApiResult;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.List;
import java.util.Map;

@FeignClient(name = "reachai-capability-model-client", url = "${services.model-service.url:http://localhost:18601}")
public interface CapabilityModelClient {

    @PostMapping("/model/chat")
    ApiResult<ChatResponse> chat(@RequestBody ChatRequest request);

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record ChatRequest(String modelInstanceId,
                       List<ChatMessage> messages,
                       JsonNode tools,
                       JsonNode toolChoice,
                       Map<String, Object> options) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record ChatMessage(String role, String content) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record ChatResponse(String content, String model, String provider, Usage usage) {
    }

    record Usage(int promptTokens, int completionTokens, int totalTokens) {
    }
}
