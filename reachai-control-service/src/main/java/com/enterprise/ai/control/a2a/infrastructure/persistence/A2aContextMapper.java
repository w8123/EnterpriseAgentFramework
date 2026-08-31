package com.enterprise.ai.control.a2a.infrastructure.persistence;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface A2aContextMapper extends BaseMapper<A2aContextEntity> {

    @Select("""
            SELECT * FROM control_a2a_context
            WHERE direction = #{direction}
              AND principal_id = #{principalId}
              AND tenant_scope = #{tenantScope}
              AND context_id = #{contextId}
            LIMIT 1
            """)
    A2aContextEntity findOwned(
            @Param("direction") String direction,
            @Param("principalId") long principalId,
            @Param("tenantScope") String tenantScope,
            @Param("contextId") String contextId);
}
