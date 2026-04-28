package com.enterprise.ai.agent.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Tool Retrieval 配置（{@code ai.tool-retrieval.*}）。
 * <p>
 * 主开关关闭或 Milvus 不可用时，AgentFactory 会回退到旧的「白名单全量注入」行为。
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "ai.tool-retrieval")
public class ToolRetrievalProperties {

    /** 主开关，关则回退白名单。 */
    private boolean enabled = true;

    /** 默认召回数。 */
    private int topK = 15;

    /** 分数低于此阈值的命中会被丢弃（COSINE 相似度 0~1）。略长文档与短问句的余弦易落在 0.2~0.35，默认不宜过高。 */
    private double minScore = 0.22;

    /** Milvus 异常时是否回退到白名单兜底。 */
    private boolean fallbackOnError = true;

    /** Milvus collection 名。 */
    private String collectionName = "tool_embeddings";

    /** 向量维度，必须与 embedding 模型输出对齐。 */
    private int embeddingDim = 1536;

    /** Embedding 使用的 provider（空则走 model-service 默认）。 */
    private String embeddingProvider;

    /** Embedding 使用的模型名（空则走 model-service 默认）。 */
    private String embeddingModel;
}
