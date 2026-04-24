package com.enterprise.ai.embedding.impl;

import com.enterprise.ai.client.ModelServiceClient;
import com.enterprise.ai.common.dto.ApiResult;
import com.enterprise.ai.embedding.EmbeddingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 通过 ai-model-service 完成 Embedding 的适配实现。
 * 当 embedding.provider=model-service 时激活，优先级高于本地 TongyiEmbeddingService。
 */
@Slf4j
@Service
@Primary
@ConditionalOnProperty(name = "embedding.provider", havingValue = "model-service", matchIfMissing = false)
@RequiredArgsConstructor
public class ModelServiceEmbeddingService implements EmbeddingService {

    private final ModelServiceClient modelServiceClient;

    @Override
    public List<Float> embed(String text) {
        return embedBatch(List.of(text)).get(0);
    }

    @Override
    public List<List<Float>> embedBatch(List<String> texts) {
        log.debug("通过 ai-model-service 进行 Embedding, 文本数量: {}", texts.size());
        ApiResult<ModelServiceClient.EmbeddingResult> result =
                modelServiceClient.embed(new ModelServiceClient.EmbeddingParam(null, null, texts));
        if (result.getCode() != 200 || result.getData() == null) {
            throw new RuntimeException("ai-model-service Embedding 调用失败: " + result.getMessage());
        }
        return result.getData().embeddings();
    }

    @Override
    public String getModelName() {
        return "model-service-proxy";
    }

    @Override
    public int getDimension() {
        return 1536;
    }
}
