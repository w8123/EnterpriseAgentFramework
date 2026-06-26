package com.enterprise.ai.agent.capability.catalog.tool.retrieval;

import com.enterprise.ai.agent.client.ModelServiceClient;
import com.enterprise.ai.agent.capability.catalog.config.ToolRetrievalProperties;
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
    private final ToolRetrievalSettingService toolRetrievalSettingService;

    public EmbeddingClient(ModelServiceClient modelServiceClient,
                           ToolRetrievalProperties properties,
                           ToolRetrievalSettingService toolRetrievalSettingService) {
        this.modelServiceClient = modelServiceClient;
        this.properties = properties;
        this.toolRetrievalSettingService = toolRetrievalSettingService;
    }

    /**
     * 解析本次向量化使用的模型实例 ID（不写库、不调模型）。
     * <p>优先级：调用方覆盖 &gt; 库表 {@code tool_retrieval_setting}（「重建向量索引」写入）
     * &gt; {@code ai.tool-retrieval.embedding-model-instance-id}（运维可选覆盖）。
     */
    public String resolveEmbeddingModelInstanceId(String modelInstanceIdOverride) {
        String id = nullIfBlank(modelInstanceIdOverride);
        if (id != null) {
            return id;
        }
        id = toolRetrievalSettingService.findEmbeddingModelInstanceId().orElse(null);
        if (id != null) {
            return id;
        }
        return nullIfBlank(properties.getEmbeddingModelInstanceId());
    }

    /**
     * 单条文本 embed；失败抛 {@link EmbeddingException}。
     */
    public List<Float> embed(String text) {
        return embed(text, null);
    }

    /**
     * @param modelInstanceIdOverride 非空时仅用于本次调用；否则见 {@link #resolveEmbeddingModelInstanceId(String)}
     */
    public List<Float> embed(String text, String modelInstanceIdOverride) {
        List<List<Float>> batch = embedBatch(List.of(text), modelInstanceIdOverride);
        if (batch == null || batch.isEmpty()) {
            throw new EmbeddingException("Embedding 返回为空");
        }
        return batch.get(0);
    }

    /**
     * 批量 embed。
     */
    public List<List<Float>> embedBatch(List<String> texts) {
        return embedBatch(texts, null);
    }

    /**
     * @param modelInstanceIdOverride 非空时优先使用，否则见 {@link #resolveEmbeddingModelInstanceId(String)}
     */
    public List<List<Float>> embedBatch(List<String> texts, String modelInstanceIdOverride) {
        if (texts == null || texts.isEmpty()) {
            return List.of();
        }
        String modelInstanceId = resolveEmbeddingModelInstanceId(modelInstanceIdOverride);
        if (modelInstanceId == null) {
            throw new EmbeddingException(
                    "未配置 Tool 向量模型实例：请先在「Tool 检索测试」页执行「重建向量索引」并选择 Embedding 模型，"
                            + "或设置 TOOL_EMBEDDING_MODEL_INSTANCE_ID / ai.tool-retrieval.embedding-model-instance-id。");
        }
        try {
            ModelServiceClient.ModelEmbeddingRequest req = ModelServiceClient.ModelEmbeddingRequest.builder()
                    .modelInstanceId(modelInstanceId)
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
