package com.enterprise.ai.control.context;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface ContextItemMapper extends BaseMapper<ContextItemEntity> {

    @Select("SELECT * FROM control_context_item WHERE id = #{id} FOR UPDATE")
    ContextItemEntity selectByIdForUpdate(@Param("id") Long id);

    @Select("""
            SELECT *
              FROM control_context_item
             WHERE namespace_id = #{namespaceId}
               AND memory_lane = 'RUNTIME_USER'
               AND status <> 'DELETED'
             ORDER BY id
             FOR UPDATE
            """)
    List<ContextItemEntity> selectPersonalByNamespaceForUpdate(@Param("namespaceId") Long namespaceId);
}
