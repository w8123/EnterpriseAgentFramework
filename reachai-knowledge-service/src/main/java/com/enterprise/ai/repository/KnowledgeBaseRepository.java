package com.enterprise.ai.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.enterprise.ai.domain.entity.KnowledgeBase;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface KnowledgeBaseRepository extends BaseMapper<KnowledgeBase> {
}
