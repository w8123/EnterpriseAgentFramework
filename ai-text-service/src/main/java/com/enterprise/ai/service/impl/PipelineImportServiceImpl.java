package com.enterprise.ai.service.impl;

import com.enterprise.ai.domain.dto.PipelineResult;
import com.enterprise.ai.pipeline.KnowledgeImportPipeline;
import com.enterprise.ai.pipeline.PipelineContext;
import com.enterprise.ai.pipeline.PipelineException;
import com.enterprise.ai.pipeline.PipelineFactory;
import com.enterprise.ai.service.PipelineImportService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Pipeline 入库服务实现 — 组装并执行知识入库流水线。
 *
 * <p>核心流程：
 * <ol>
 *   <li>通过 {@link PipelineFactory} 根据知识库编码动态组装 Pipeline</li>
 *   <li>执行 Pipeline（按步骤顺序）</li>
 *   <li>封装执行结果返回</li>
 * </ol></p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PipelineImportServiceImpl implements PipelineImportService {

    private final PipelineFactory pipelineFactory;

    @Override
    public PipelineResult execute(PipelineContext context) {
        String kbCode = context.getKnowledgeBaseCode();
        String fileId = context.getFileId();

        try {
            // 根据知识库编码动态组装 Pipeline
            KnowledgeImportPipeline pipeline = pipelineFactory.create(kbCode);

            // 执行流水线
            pipeline.execute(context);

            // 判断是否中途被中断
            if (context.isAborted()) {
                log.warn("Pipeline 执行被中断: fileId={}, 原因: {}", fileId, context.getAbortReason());
                return PipelineResult.builder()
                        .fileId(fileId)
                        .knowledgeBaseCode(kbCode)
                        .chunkCount(context.getChunks() != null ? context.getChunks().size() : 0)
                        .vectorCount(context.getVectorIds() != null ? context.getVectorIds().size() : 0)
                        .stepDurations(context.getStepDurations())
                        .status("ABORTED")
                        .errorMessage(context.getAbortReason())
                        .build();
            }

            return PipelineResult.builder()
                    .fileId(fileId)
                    .knowledgeBaseCode(kbCode)
                    .chunkCount(context.getChunks() != null ? context.getChunks().size() : 0)
                    .vectorCount(context.getVectorIds() != null ? context.getVectorIds().size() : 0)
                    .stepDurations(context.getStepDurations())
                    .status("SUCCESS")
                    .build();

        } catch (PipelineException e) {
            log.error("Pipeline 执行失败: fileId={}, step={}", fileId, e.getStepName(), e);
            return PipelineResult.builder()
                    .fileId(fileId)
                    .knowledgeBaseCode(kbCode)
                    .chunkCount(context.getChunks() != null ? context.getChunks().size() : 0)
                    .vectorCount(context.getVectorIds() != null ? context.getVectorIds().size() : 0)
                    .stepDurations(context.getStepDurations())
                    .status("FAILED")
                    .errorMessage(e.getMessage())
                    .build();

        } catch (Exception e) {
            log.error("Pipeline 执行异常: fileId={}", fileId, e);
            return PipelineResult.builder()
                    .fileId(fileId)
                    .knowledgeBaseCode(kbCode)
                    .stepDurations(context.getStepDurations())
                    .status("FAILED")
                    .errorMessage("系统异常: " + e.getMessage())
                    .build();
        }
    }
}
