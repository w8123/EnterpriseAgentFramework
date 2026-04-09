package com.enterprise.ai.rag.impl;

import com.enterprise.ai.client.ModelServiceClient;
import com.enterprise.ai.common.dto.ApiResult;
import com.enterprise.ai.rag.LlmService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * 通过 ai-model-service 完成 LLM Chat 的适配实现。
 * 当 llm.provider=model-service 时激活。
 */
@Slf4j
@Service
@Primary
@ConditionalOnProperty(name = "llm.provider", havingValue = "model-service", matchIfMissing = false)
@RequiredArgsConstructor
public class ModelServiceLlmService implements LlmService {

    private final ModelServiceClient modelServiceClient;

    @Override
    public String chat(String prompt) {
        log.debug("通过 ai-model-service 进行 LLM Chat");
        Map<String, Object> request = Map.of(
                "messages", List.of(Map.of("role", "user", "content", prompt))
        );
        ApiResult<Map<String, Object>> result = modelServiceClient.chat(request);
        if (result.getCode() != 200 || result.getData() == null) {
            throw new RuntimeException("ai-model-service Chat 调用失败: " + result.getMessage());
        }
        return (String) result.getData().get("content");
    }

    @Override
    public String getModelName() {
        return "model-service-proxy";
    }
}
