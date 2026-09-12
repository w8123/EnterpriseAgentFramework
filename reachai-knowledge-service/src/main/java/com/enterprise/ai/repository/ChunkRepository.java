package com.enterprise.ai.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.enterprise.ai.domain.entity.Chunk;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Options;

@Mapper
public interface ChunkRepository extends BaseMapper<Chunk> {
    @Select("SELECT * FROM knowledge_chunk WHERE id=#{id} FOR UPDATE")
    @Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
    Chunk lockById(@Param("id") Long id);

    @Select("SELECT COALESCE(SUM(hit_count), 0) FROM knowledge_chunk WHERE knowledge_base_id = #{knowledgeBaseId}")
    Long sumHitCount(@Param("knowledgeBaseId") Long knowledgeBaseId);
}
