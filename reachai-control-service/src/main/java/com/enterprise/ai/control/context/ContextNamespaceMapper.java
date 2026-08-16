package com.enterprise.ai.control.context;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface ContextNamespaceMapper extends BaseMapper<ContextNamespaceEntity> {

    @Select("SELECT id FROM control_context_namespace WHERE id = #{id} FOR UPDATE")
    Long lockById(@Param("id") Long id);
}
