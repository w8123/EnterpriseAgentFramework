package com.enterprise.ai.service.impl;

import com.enterprise.ai.domain.dto.ChunkVO;
import com.enterprise.ai.domain.entity.Chunk;
import org.springframework.beans.BeanUtils;

/** Shared chunk view mapping for management commands and operational queries. */
public final class KnowledgeChunkProjection {
    private KnowledgeChunkProjection() { }

    public static ChunkVO toChunkVO(Chunk c) {
        ChunkVO vo = new ChunkVO();
        BeanUtils.copyProperties(c, vo);
        vo.setLength(c.getContent() != null ? c.getContent().length() : 0);
        return vo;
    }
}
