package com.enterprise.ai.pipeline.step;

import com.enterprise.ai.pipeline.PipelineContext;
import com.enterprise.ai.pipeline.PipelineException;
import com.enterprise.ai.pipeline.PipelineStep;
import com.enterprise.ai.vector.VectorService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.IntStream;
import java.util.stream.Collectors;

/**
 * 步骤六：向量入库 — 将向量写入 Milvus。
 *
 * <p>根据 knowledgeBaseCode 选择对应的 collection，
 * 自动创建（如不存在），并写入向量及元数据（file_id、content）。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class VectorStoreStep implements PipelineStep {

    private final VectorService vectorService;

    @Value("${milvus.dimension:1536}")
    private int defaultDimension;

    @Override
    public void process(PipelineContext context) {
        List<List<Float>> vectors = context.getVectors();
        List<String> chunks = context.getChunks();

        if (vectors == null || vectors.isEmpty()) {
            throw new PipelineException(getName(), context.getFileId(), "向量为空，无法入库");
        }

        String collectionName = context.getKnowledgeBaseCode();
        String fileId = context.getFileId();

        // 确保 collection 存在
        vectorService.ensureCollection(collectionName, defaultDimension);

        // 生成向量 ID
        List<String> vectorIds = IntStream.range(0, vectors.size())
                .mapToObj(i -> fileId + "_chunk_" + i)
                .collect(Collectors.toList());

        // file_id 列表（每条向量都关联同一个 fileId）
        List<String> fileIds = Collections.nCopies(vectors.size(), fileId);

        // 写入 Milvus
        vectorService.insert(collectionName, vectorIds, vectors, fileIds, chunks);

        context.setVectorIds(vectorIds);
        log.debug("VectorStoreStep 完成: fileId={}, collection={}, 向量数={}",
                fileId, collectionName, vectorIds.size());
    }

    @Override
    public String getName() {
        return "VECTOR_STORE";
    }
}
