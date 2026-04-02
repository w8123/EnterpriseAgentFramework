package com.enterprise.ai.pipeline;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Pipeline 工厂 — 根据知识库编码动态组装流水线。
 *
 * <p>工作流程：
 * <ol>
 *   <li>从 {@link PipelineProperties} 读取知识库对应的步骤列表</li>
 *   <li>根据步骤名称从 Spring 容器中查找对应的 {@link PipelineStep} Bean</li>
 *   <li>按顺序组装为 {@link KnowledgeImportPipeline} 实例</li>
 * </ol>
 * 找不到知识库专属配置时，使用 "default" 配置。</p>
 *
 * <h3>示例</h3>
 * <pre>
 * KnowledgeImportPipeline pipeline = pipelineFactory.create("kb_contract");
 * pipeline.execute(context);
 * </pre>
 */
@Slf4j
@Component
public class PipelineFactory {

    private final PipelineProperties pipelineProperties;

    /** 所有 PipelineStep Bean 按名称索引（自动收集） */
    private final Map<String, PipelineStep> stepBeanMap;

    /**
     * 构造时由 Spring 注入所有 PipelineStep Bean 列表，转换为 name → bean 映射
     */
    public PipelineFactory(PipelineProperties pipelineProperties, List<PipelineStep> allSteps) {
        this.pipelineProperties = pipelineProperties;
        this.stepBeanMap = new HashMap<>();
        for (PipelineStep step : allSteps) {
            this.stepBeanMap.put(step.getName(), step);
            log.debug("注册 Pipeline 步骤: {} → {}", step.getName(), step.getClass().getSimpleName());
        }
    }

    /**
     * 根据知识库编码创建对应的 Pipeline 实例
     *
     * @param knowledgeBaseCode 知识库编码（如 "kb_contract"），
     *                          找不到配置时使用 "default"
     * @return 组装好的 Pipeline 实例
     */
    public KnowledgeImportPipeline create(String knowledgeBaseCode) {
        PipelineProperties.PipelineDefinition definition =
                pipelineProperties.getDefinitions().get(knowledgeBaseCode);

        if (definition == null) {
            log.info("未找到知识库 {} 的 Pipeline 配置, 使用 default", knowledgeBaseCode);
            definition = pipelineProperties.getDefinitions().get("default");
        }

        if (definition == null || definition.getSteps() == null || definition.getSteps().isEmpty()) {
            throw new IllegalStateException(
                    "未找到 Pipeline 配置: " + knowledgeBaseCode + " (且 default 也未配置)");
        }

        KnowledgeImportPipeline pipeline = new KnowledgeImportPipeline(knowledgeBaseCode);

        for (String stepName : definition.getSteps()) {
            PipelineStep step = stepBeanMap.get(stepName);
            if (step == null) {
                throw new IllegalStateException(
                        "Pipeline 步骤未注册: " + stepName + " (知识库: " + knowledgeBaseCode + ")");
            }
            pipeline.addStep(step);
        }

        log.info("Pipeline 组装完成: {} → {}", knowledgeBaseCode, definition.getSteps());
        return pipeline;
    }
}
