package com.enterprise.ai.pipeline.step;

import com.enterprise.ai.pipeline.PipelineContext;
import com.enterprise.ai.pipeline.PipelineException;
import com.enterprise.ai.pipeline.PipelineStep;
import com.enterprise.ai.vector.VectorService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;
import java.util.stream.IntStream;
import java.util.stream.Collectors;
import com.enterprise.ai.pipeline.document.job.DocumentIndexVectorManifest;
import com.enterprise.ai.pipeline.document.job.DocumentIndexExecutionStore;

/**
 * 步骤六：向量入库 — 将向量写入 Milvus。
 *
 * <p>使用执行入口确认的物理 collection，
 * 仅写入已发布知识库的向量及元数据；集合创建由独立生命周期组件负责。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class VectorStoreStep implements PipelineStep {

    private final VectorService vectorService;
    private final DocumentIndexExecutionStore executions;

    @Override
    public void process(PipelineContext context) {
        if (org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("向量写入不能持有数据库事务");
        }
        List<List<Float>> vectors = context.getVectors();
        List<String> chunks = context.getChunks();

        if (vectors == null || vectors.isEmpty() || chunks == null || chunks.size() != vectors.size()) {
            throw new PipelineException(getName(), context.getFileId(), "向量为空，无法入库");
        }

        String collectionName = context.getVectorCollectionName();
        if (context.getKnowledgeBaseId() == null || collectionName == null || collectionName.isBlank()) {
            throw new PipelineException(getName(), context.getFileId(), "导入缺少已确认的知识库和物理集合身份");
        }
        String fileId = context.getFileId();

        // 生成向量 ID
        String identity = DocumentIndexExecutionStore.executionId(context);
        var manifest = DocumentIndexVectorManifest.forExecution(fileId, identity, vectors.size());
        String vectorPrefix = manifest.prefix();
        List<String> vectorIds = IntStream.range(0, vectors.size())
                .mapToObj(i -> vectorPrefix + "_chunk_" + i)
                .collect(Collectors.toList());

        if (!executions.register(context, manifest)) {
            throw new PipelineException(getName(), fileId, "本次执行已登记，禁止重复发起向量写入");
        }
        // 登记独立提交后才调用外部存储；同一次执行只有首次登记者可写入。
        // file_id 列表（每条向量都关联同一个 fileId）
        List<String> fileIds = Collections.nCopies(vectors.size(), fileId);

        // 主键可幂等删除，但写请求不能在同一执行下重新发起。
        context.setVectorIds(vectorIds);
        vectorService.upsert(collectionName, vectorIds, vectors, fileIds, chunks);
        executions.acknowledge(identity);
        log.debug("VectorStoreStep 完成: fileId={}, collection={}, 向量数={}",
                fileId, collectionName, vectorIds.size());
    }

    @Override
    public String getName() {
        return "VECTOR_STORE";
    }

}
