package com.enterprise.ai.service.impl;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.enterprise.ai.domain.dto.ChunkUpdateRequest;
import com.enterprise.ai.domain.dto.ChunkVO;
import com.enterprise.ai.domain.entity.Chunk;
import com.enterprise.ai.repository.ChunkRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import static com.enterprise.ai.service.impl.KnowledgeChunkProjection.toChunkVO;

/** 知识片段人工编辑；保留字段更新、归属条件及调用方事务。 */
@Service
@RequiredArgsConstructor
public class KnowledgeChunkEditingService {
    private final ChunkRepository chunkRepository;

    @Transactional(rollbackFor = Exception.class)
    public ChunkVO updateChunk(Long chunkId, ChunkUpdateRequest request) {
        Chunk chunk = findChunk(chunkId);
        var change = new LambdaUpdateWrapper<Chunk>().eq(Chunk::getId, chunkId)
                .eq(Chunk::getKnowledgeBaseId, chunk.getKnowledgeBaseId()).eq(Chunk::getFileId, chunk.getFileId());
        boolean changed = false;
        if (request.getTitle() != null) {
            change.set(Chunk::getTitle, request.getTitle()); changed = true;
        }
        if (request.getContent() != null) {
            if (request.getContent().isBlank()) {
                throw new IllegalArgumentException("chunk content cannot be blank");
            }
            change.set(Chunk::getContent, request.getContent()); changed = true;
        }
        if (request.getEnabled() != null) {
            change.set(Chunk::getEnabled, request.getEnabled() == 0 ? 0 : 1); changed = true;
        }
        if (!changed) return toChunkVO(chunk);
        if (chunkRepository.update(null, change) != 1) throw new IllegalStateException("知识片段已删除或归属已变化");
        return toChunkVO(chunkRepository.lockById(chunkId));
    }

    @Transactional(rollbackFor = Exception.class)
    public ChunkVO toggleChunk(Long chunkId, Integer enabled) {
        Chunk chunk = findChunk(chunkId);
        if (chunkRepository.update(null, new LambdaUpdateWrapper<Chunk>().eq(Chunk::getId, chunkId)
                .eq(Chunk::getKnowledgeBaseId, chunk.getKnowledgeBaseId()).eq(Chunk::getFileId, chunk.getFileId())
                .set(Chunk::getEnabled, enabled != null && enabled == 0 ? 0 : 1)) != 1) {
            throw new IllegalStateException("知识片段已删除或归属已变化");
        }
        return toChunkVO(chunkRepository.lockById(chunkId));
    }

    private Chunk findChunk(Long chunkId) {
        Chunk chunk = chunkRepository.selectById(chunkId);
        if (chunk == null) {
            throw new IllegalArgumentException("chunk not found: " + chunkId);
        }
        return chunk;
    }

}
