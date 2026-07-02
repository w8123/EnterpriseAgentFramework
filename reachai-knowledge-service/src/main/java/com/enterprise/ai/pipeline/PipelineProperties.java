package com.enterprise.ai.pipeline;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Pipeline 配置属性 — 从 application.yml 中读取各知识库的流水线步骤定义。
 *
 * <h3>配置示例</h3>
 * <pre>
 * pipeline:
 *   definitions:
 *     default:
 *       steps: [FILE_PARSE, TEXT_CLEAN, CHUNK, EMBEDDING, VECTOR_STORE, METADATA_PERSIST]
 *     kb_contract:
 *       steps: [FILE_PARSE, TEXT_CLEAN, CHUNK, EMBEDDING, VECTOR_STORE, METADATA_PERSIST]
 *     kb_scan:
 *       steps: [FILE_PARSE, OCR, TEXT_CLEAN, CHUNK, EMBEDDING, VECTOR_STORE, METADATA_PERSIST]
 * </pre>
 *
 * <p>每个知识库编码对应一个 Pipeline 定义，不在列表中的知识库使用 "default" 配置。</p>
 */
@Data
@Component
@ConfigurationProperties(prefix = "pipeline")
public class PipelineProperties {

    /**
     * 知识库编码 → Pipeline 步骤定义
     */
    private Map<String, PipelineDefinition> definitions = new HashMap<>();

    @Data
    public static class PipelineDefinition {
        /** 步骤名称列表（有序），名称与 PipelineStep.getName() 对应 */
        private List<String> steps;
    }
}
