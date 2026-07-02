package com.enterprise.ai.pipeline;

import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 知识入库 Pipeline 执行器。
 *
 * <p>按步骤顺序执行，每个步骤独立异常处理，支持中途中断。
 * 执行完成后可通过 {@link PipelineContext#getStepDurations()} 查看各步骤耗时。</p>
 *
 * <h3>使用方式</h3>
 * <pre>
 * KnowledgeImportPipeline pipeline = new KnowledgeImportPipeline("kb_contract");
 * pipeline.addStep(fileParseStep);
 * pipeline.addStep(textCleanStep);
 * pipeline.addStep(chunkStep);
 * pipeline.addStep(embeddingStep);
 * pipeline.addStep(vectorStoreStep);
 * pipeline.execute(context);
 * </pre>
 */
@Slf4j
public class KnowledgeImportPipeline {

    /** Pipeline 标识（通常为知识库编码或 "default"） */
    private final String name;

    /** 有序步骤列表 */
    private final List<PipelineStep> steps = new ArrayList<>();

    public KnowledgeImportPipeline(String name) {
        this.name = name;
    }

    /**
     * 添加步骤到流水线末尾
     */
    public KnowledgeImportPipeline addStep(PipelineStep step) {
        steps.add(step);
        return this;
    }

    /**
     * 在指定位置插入步骤（用于动态调整流水线）
     */
    public KnowledgeImportPipeline insertStep(int index, PipelineStep step) {
        steps.add(index, step);
        return this;
    }

    /**
     * 执行整个流水线
     *
     * @param context 入库上下文
     * @throws PipelineException 当某步骤失败时抛出，携带步骤名与文件信息
     */
    public void execute(PipelineContext context) {
        String stepNames = steps.stream().map(PipelineStep::getName).collect(Collectors.joining(" → "));
        log.info("Pipeline[{}] 开始执行, fileId={}, 步骤链: {}", name, context.getFileId(), stepNames);

        long pipelineStart = System.currentTimeMillis();

        for (PipelineStep step : steps) {
            if (context.isAborted()) {
                log.warn("Pipeline[{}] 已中断, 跳过步骤 {}, 原因: {}",
                        name, step.getName(), context.getAbortReason());
                break;
            }

            context.setCurrentStep(step.getName());
            long stepStart = System.currentTimeMillis();

            try {
                log.info("Pipeline[{}] >> 执行步骤: {}", name, step.getName());
                step.process(context);
                long duration = System.currentTimeMillis() - stepStart;
                context.recordDuration(step.getName(), duration);
                log.info("Pipeline[{}] << 步骤 {} 完成, 耗时 {}ms", name, step.getName(), duration);
            } catch (PipelineException e) {
                long duration = System.currentTimeMillis() - stepStart;
                context.recordDuration(step.getName(), duration);
                log.error("Pipeline[{}] !! 步骤 {} 失败, 耗时 {}ms", name, step.getName(), duration, e);
                throw e;
            } catch (Exception e) {
                long duration = System.currentTimeMillis() - stepStart;
                context.recordDuration(step.getName(), duration);
                log.error("Pipeline[{}] !! 步骤 {} 异常, 耗时 {}ms", name, step.getName(), duration, e);
                throw new PipelineException(step.getName(), context.getFileId(),
                        "步骤执行异常: " + e.getMessage(), e);
            }
        }

        long totalDuration = System.currentTimeMillis() - pipelineStart;
        log.info("Pipeline[{}] 执行完毕, fileId={}, 总耗时 {}ms, 各步骤耗时: {}",
                name, context.getFileId(), totalDuration, context.getStepDurations());
    }

    public String getName() {
        return name;
    }

    public List<PipelineStep> getSteps() {
        return steps;
    }
}
