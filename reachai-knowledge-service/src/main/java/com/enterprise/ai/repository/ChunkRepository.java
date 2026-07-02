package com.enterprise.ai.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.enterprise.ai.domain.entity.Chunk;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ChunkRepository extends BaseMapper<Chunk> {
}
