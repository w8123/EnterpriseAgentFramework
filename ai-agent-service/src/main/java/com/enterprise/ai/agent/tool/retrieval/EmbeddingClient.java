package com.enterprise.ai.agent.tool.retrieval;

import com.enterprise.ai.agent.client.ModelServiceClient;
import com.enterprise.ai.agent.config.ToolRetrievalProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Embedding 调用封装：委托 {@link ModelServiceClient} 的 {@code /model/embedding} 端点。
 * <p>
 * 模型网关集中在 ai-model-service（根文档「目标架构」要求），因此 ai-agent-service 不自己持有 API Key，
 * 也不经过 ai-skills-service 多跳一次 HTTP。
 */
@Slf4j
@Component
public class EmbeddingClient {

    private final ModelServiceClient modelServiceClient;
    private final ToolRetrievalProperties properties;

    public EmbeddingClient(ModelServiceClient modelServiceClient,
                           ToolRetrievalProperties properties) {
        this.modelServiceClient = modelServiceClient;
        this.properties = properties;
    }

    /**
     * 单条文本 embed；失败抛 {@link EmbeddingException}。
     */
    public List<Float> embed(String text) {
        List<List<Float>> batch = embedBatch(List.of(text));
        if (batch == null || batch.isEmpty()) {
            throw new EmbeddingException("Embedding 返回为空");
        }
        return batch.get(0);
    }

    /**
     * 批量 embed。
     */
    public List<List<Float>> embedBatch(List<String> texts) {
        if (texts == null || texts.isEmpty()) {
            return List.of();
        }
        try {
            ModelServiceClient.ModelEmbeddingRequest req = ModelServiceClient.ModelEmbeddingRequest.builder()
                    .provider(nullIfBlank(properties.getEmbeddingProvider()))
                    .model(nullIfBlank(properties.getEmbeddingModel()))
                    .texts(texts)
                    .build();
            ModelServiceClient.ModelEmbeddingResult result = modelServiceClient.embed(req);
            if (result == null || result.getData() == null || result.getData().getEmbeddings() == null) {
                throw new EmbeddingException("Embedding 响应数据为空");
            }
            return result.getData().getEmbeddings();
        } catch (EmbeddingException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new EmbeddingException("Embedding 调用失败: " + ex.getMessage(), ex);
        }
    }

    private static String nullIfBlank(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }

    /** 区分 embed 异常与 Milvus 异常，便于上层降级决策。 */
    public static class EmbeddingException extends RuntimeException {
        public EmbeddingException(String message) {
            super(message);
        }

        public EmbeddingException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
