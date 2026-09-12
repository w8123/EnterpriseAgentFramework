package com.enterprise.ai.service.impl;

import com.enterprise.ai.domain.dto.PipelineResult;
import com.enterprise.ai.pipeline.KnowledgeImportPipeline;
import com.enterprise.ai.pipeline.PipelineContext;
import com.enterprise.ai.pipeline.PipelineException;
import com.enterprise.ai.pipeline.PipelineFactory;
import com.enterprise.ai.service.PipelineImportService;
import com.enterprise.ai.repository.KnowledgeBaseLookup;
import java.util.Objects;
import static com.enterprise.ai.domain.KnowledgeBaseSettings.requireVectorCollectionName;
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
    private final KnowledgeBaseLookup knowledgeBaseLookup;
    private final com.enterprise.ai.pipeline.document.job.DocumentIndexExecutionStore executions;

    @Override
    @org.springframework.transaction.annotation.Transactional(propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    public PipelineResult execute(PipelineContext context) {
        String kbCode = context.getKnowledgeBaseCode();
        String fileId = context.getFileId();

        try {
            if (context.getImportJobId() != null && (context.getKnowledgeBaseId() == null
                    || context.getVectorCollectionName() == null || context.getVectorCollectionName().isBlank())) {
                throw new IllegalArgumentException("异步导入缺少提交时的知识库和物理集合身份");
            }
            var kb = context.getKnowledgeBaseId() == null ? knowledgeBaseLookup.requireByCode(kbCode)
                    : knowledgeBaseLookup.requireById(context.getKnowledgeBaseId());
            if (!Objects.equals(kbCode, kb.getCode())) throw new IllegalArgumentException("导入知识库身份不匹配");
            String physical = requireVectorCollectionName(kb);
            if (context.getImportJobId() != null && !Objects.equals(context.getVectorCollectionName(), physical)) {
                throw new IllegalArgumentException("原知识库物理集合已变化，拒绝执行旧导入任务");
            }
            context.setKnowledgeBaseId(kb.getId());
            context.setVectorCollectionName(physical);
            context.setKnowledgeBaseDimension(kb.getDimension());
            if (context.getImportJobId() == null) {
                if (context.getImportLeaseOwner() != null) throw new IllegalArgumentException("非任务导入不能携带任务租约");
                context.setIndexExecutionId(java.util.UUID.randomUUID().toString());
                context.setIndexOperation("PIPELINE");
                context.setIndexTarget(null);
            }

            // 根据知识库编码动态组装 Pipeline
            KnowledgeImportPipeline pipeline = pipelineFactory.create(kbCode);
            if (pipeline.getSteps().isEmpty()
                    || !"METADATA_PERSIST".equals(pipeline.getSteps().get(pipeline.getSteps().size() - 1).getName())
                    || pipeline.getSteps().stream().filter(step -> "METADATA_PERSIST".equals(step.getName())).count() != 1) {
                throw new IllegalStateException("导入任务流水线必须且只能在最后执行一次元数据发布");
            }

            // 执行流水线
            pipeline.execute(context);

            // 判断是否中途被中断
            if (context.isAborted()) {
                log.warn("Pipeline 执行被中断: fileId={}, 原因: {}", fileId, context.getAbortReason());
                return PipelineResult.builder()
                        .fileId(fileId)
                        .importJobId(context.getImportJobId())
                        .knowledgeBaseCode(kbCode)
                        .chunkCount(context.getChunks() != null ? context.getChunks().size() : 0)
                        .vectorCount(context.getVectorIds() != null ? context.getVectorIds().size() : 0)
                        .stepDurations(context.getStepDurations())
                        .status("ABORTED")
                        .errorMessage(context.getAbortReason())
                        .build();
            }

            if (!context.isImportPublished()) throw new PipelineException("METADATA_PERSIST", fileId, "流水线未提交索引发布事务");
            return PipelineResult.builder()
                    .fileId(fileId)
                    .importJobId(context.getImportJobId())
                    .knowledgeBaseCode(kbCode)
                    .chunkCount(context.getChunks() != null ? context.getChunks().size() : 0)
                    .vectorCount(context.getVectorIds() != null ? context.getVectorIds().size() : 0)
                    .stepDurations(context.getStepDurations())
                    .status("SUCCESS")
                    .providerType(context.getParsedDocument() == null || context.getParsedDocument().getProviderType() == null
                            ? null : context.getParsedDocument().getProviderType().name())
                    .providerVersion(context.getParsedDocument() == null ? null
                            : context.getParsedDocument().getProviderVersion())
                    .build();

        } catch (PipelineException e) {
            log.error("Pipeline 执行失败: fileId={}, step={}", fileId, e.getStepName(), e);
            return PipelineResult.builder()
                    .fileId(fileId)
                    .importJobId(context.getImportJobId())
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
                    .importJobId(context.getImportJobId())
                    .knowledgeBaseCode(kbCode)
                    .stepDurations(context.getStepDurations())
                    .status("FAILED")
                    .errorMessage("系统异常: " + e.getMessage())
                    .build();
        } finally {
            if (context.getImportJobId() == null && !context.isImportPublished()) {
                try { executions.abandonStandalone(context); }
                catch (RuntimeException cleanupFailure) {
                    log.warn("索引执行将按持久化截止时间恢复: execution={}, errorType={}",
                            context.getIndexExecutionId(), cleanupFailure.getClass().getSimpleName());
                }
            }
        }
    }
}
