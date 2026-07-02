package com.enterprise.ai.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.enterprise.ai.domain.entity.KnowledgeTag;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface KnowledgeTagRepository extends BaseMapper<KnowledgeTag> {
}
