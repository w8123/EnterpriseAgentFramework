package com.enterprise.ai.capability.catalog.retrieval;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Service
@RequiredArgsConstructor
public class CapabilityToolRetrievalSettingService {

    private final CapabilityToolRetrievalSettingMapper mapper;

    public Optional<String> findEmbeddingModelInstanceId() {
        CapabilityToolRetrievalSettingEntity row = mapper.selectById(CapabilityToolRetrievalSettingEntity.SINGLETON_ID);
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
        CapabilityToolRetrievalSettingEntity row = mapper.selectById(CapabilityToolRetrievalSettingEntity.SINGLETON_ID);
        if (row == null) {
            CapabilityToolRetrievalSettingEntity insert = new CapabilityToolRetrievalSettingEntity();
            insert.setId(CapabilityToolRetrievalSettingEntity.SINGLETON_ID);
            insert.setEmbeddingModelInstanceId(id);
            mapper.insert(insert);
        } else {
            row.setEmbeddingModelInstanceId(id);
            mapper.updateById(row);
        }
    }
}
