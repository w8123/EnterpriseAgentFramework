package com.enterprise.ai.control.client.model;

import com.enterprise.ai.common.dto.ApiResult;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.List;
import java.util.Map;

/** One-shot chat completion for Control-side summaries (主动感知 AI 归纳). */
@FeignClient(name = "reachai-control-model-chat", url = "${services.model-service.url:http://localhost:18601}",
        configuration = ModelInternalAuthFeignConfig.class)
public interface ControlModelChatClient {

    @PostMapping("/model/chat")
    ApiResult<ChatResponse> chat(@RequestBody ChatRequest request);

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record ChatRequest(String modelInstanceId, List<ChatMessage> messages, Map<String, Object> options) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record ChatMessage(String role, String content) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ChatResponse(String content, String model, String provider) {
    }
}
