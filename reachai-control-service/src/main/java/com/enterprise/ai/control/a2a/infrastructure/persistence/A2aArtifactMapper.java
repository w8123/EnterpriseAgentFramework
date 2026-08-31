package com.enterprise.ai.control.a2a.infrastructure.persistence;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface A2aArtifactMapper extends BaseMapper<A2aArtifactEntity> {

    @Select("""
            SELECT a.*
            FROM control_a2a_artifact a
            INNER JOIN control_a2a_task t ON t.id = a.task_ref_id
            WHERE a.task_ref_id = #{taskRefId}
              AND t.direction = #{direction}
              AND t.principal_id = #{principalId}
              AND t.tenant_scope = #{tenantScope}
            ORDER BY a.created_at ASC, a.id ASC
            """)
    List<A2aArtifactEntity> findForTask(
            @Param("direction") String direction,
            @Param("principalId") long principalId,
            @Param("tenantScope") String tenantScope,
            @Param("taskRefId") long taskRefId);
}
