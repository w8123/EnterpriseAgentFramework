package com.enterprise.ai.embedding.impl;

import com.enterprise.ai.client.ModelServiceClient;
import com.enterprise.ai.common.dto.ApiResult;
import com.enterprise.ai.embedding.EmbeddingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;

@Slf4j
@Service
@Primary
@RequiredArgsConstructor
public class ModelServiceEmbeddingService implements EmbeddingService {

    private final ModelServiceClient modelServiceClient;

    @Override
    public List<Float> embed(String modelInstanceId, String text) {
        return embedBatch(modelInstanceId, List.of(text)).get(0);
    }

    @Override
    public List<List<Float>> embedBatch(String modelInstanceId, List<String> texts) {
        if (!StringUtils.hasText(modelInstanceId)) {
            throw new IllegalArgumentException("modelInstanceId is required for embedding");
        }
        log.debug("Embedding through reachai-model-service, modelInstanceId={}, texts={}", modelInstanceId, texts.size());
        ApiResult<ModelServiceClient.EmbeddingResult> result =
                modelServiceClient.embed(new ModelServiceClient.EmbeddingParam(modelInstanceId, texts));
        if (result.getCode() != 200 || result.getData() == null) {
            throw new RuntimeException("reachai-model-service embedding failed: " + result.getMessage());
        }
        return result.getData().embeddings();
    }
}
