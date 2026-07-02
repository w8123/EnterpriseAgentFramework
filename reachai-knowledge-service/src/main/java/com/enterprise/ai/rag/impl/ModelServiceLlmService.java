package com.enterprise.ai.rag.impl;

import com.enterprise.ai.client.ModelServiceClient;
import com.enterprise.ai.common.dto.ApiResult;
import com.enterprise.ai.rag.LlmService;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Service
@Primary
@RequiredArgsConstructor
public class ModelServiceLlmService implements LlmService {

    private final ModelServiceClient modelServiceClient;

    @Override
    public String chat(String prompt, String modelInstanceId) {
        String required = requireModelInstanceId(modelInstanceId);
        Map<String, Object> request = Map.of(
                "modelInstanceId", required,
                "messages", List.of(Map.of("role", "user", "content", prompt))
        );
        ApiResult<Map<String, Object>> result = modelServiceClient.chat(request);
        if (result.getCode() != 200 || result.getData() == null) {
            throw new RuntimeException("reachai-model-service chat failed: " + result.getMessage());
        }
        return (String) result.getData().get("content");
    }

    @Override
    public String getModelName(String modelInstanceId) {
        return requireModelInstanceId(modelInstanceId);
    }

    private String requireModelInstanceId(String modelInstanceId) {
        if (modelInstanceId == null || modelInstanceId.isBlank()) {
            throw new IllegalStateException("modelInstanceId is required for RAG generation");
        }
        return modelInstanceId.trim();
    }
}
