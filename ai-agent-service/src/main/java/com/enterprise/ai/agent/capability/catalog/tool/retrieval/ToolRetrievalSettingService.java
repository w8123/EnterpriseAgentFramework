package com.enterprise.ai.agent.capability.catalog.tool.retrieval;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * 读写 {@link ToolRetrievalSettingEntity}：持久化「重建向量索引」选用的 Embedding 模型实例 ID。
 */
@Service
@RequiredArgsConstructor
public class ToolRetrievalSettingService {

    private final ToolRetrievalSettingMapper mapper;

    public Optional<String> findEmbeddingModelInstanceId() {
        ToolRetrievalSettingEntity row = mapper.selectById(ToolRetrievalSettingEntity.SINGLETON_ID);
        if (row == null || row.getEmbeddingModelInstanceId() == null || row.getEmbeddingModelInstanceId().isBlank()) {
            return Optional.empty();
        }
        return Optional.of(row.getEmbeddingModelInstanceId().trim());
    }

    @Transactional
    public void saveEmbeddingModelInstanceId(String embeddingModelInstanceId) {
        if (embeddingModelInstanceId == null || embeddingModelInstanceId.isBlank()) {
            return;
        }
        String id = embeddingModelInstanceId.trim();
        ToolRetrievalSettingEntity row = mapper.selectById(ToolRetrievalSettingEntity.SINGLETON_ID);
        if (row == null) {
            ToolRetrievalSettingEntity insert = new ToolRetrievalSettingEntity();
            insert.setId(ToolRetrievalSettingEntity.SINGLETON_ID);
            insert.setEmbeddingModelInstanceId(id);
            mapper.insert(insert);
        } else {
            row.setEmbeddingModelInstanceId(id);
            mapper.updateById(row);
        }
    }
}
