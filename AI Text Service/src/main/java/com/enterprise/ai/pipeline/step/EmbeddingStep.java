package com.enterprise.ai.pipeline.step;

import com.enterprise.ai.embedding.EmbeddingService;
import com.enterprise.ai.pipeline.PipelineContext;
import com.enterprise.ai.pipeline.PipelineException;
import com.enterprise.ai.pipeline.PipelineStep;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 步骤五：文本向量化 — 对所有 chunk 进行批量 Embedding。
 *
 * <p>调用 {@link EmbeddingService#embedBatch(List)} 将文本块转换为向量，
 * 结果写入 {@link PipelineContext#setVectors(List)}。</p>
 *
 * <p>EmbeddingService 是可扩展接口，当前使用通义实现，
 * 可随时切换为 OpenAI、BGE 等模型。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EmbeddingStep implements PipelineStep {

    private final EmbeddingService embeddingService;

    @Override
    public void process(PipelineContext context) {
        List<String> chunks = context.getChunks();
        if (chunks == null || chunks.isEmpty()) {
            throw new PipelineException(getName(), context.getFileId(), "chunks 为空，无法向量化");
        }

        List<List<Float>> vectors = embeddingService.embedBatch(chunks);

        if (vectors.size() != chunks.size()) {
            throw new PipelineException(getName(), context.getFileId(),
                    String.format("向量数量(%d)与chunk数量(%d)不匹配", vectors.size(), chunks.size()));
        }

        context.setVectors(vectors);
        log.debug("EmbeddingStep 完成: fileId={}, 向量数量={}, 维度={}",
                context.getFileId(), vectors.size(),
                vectors.isEmpty() ? 0 : vectors.get(0).size());
    }

    @Override
    public String getName() {
        return "EMBEDDING";
    }
}
