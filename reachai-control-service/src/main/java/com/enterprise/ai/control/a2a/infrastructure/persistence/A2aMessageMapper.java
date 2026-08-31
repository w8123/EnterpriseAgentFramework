package com.enterprise.ai.control.a2a.infrastructure.persistence;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface A2aMessageMapper extends BaseMapper<A2aMessageEntity> {

    @Select("""
            SELECT * FROM control_a2a_message
            WHERE direction = #{direction}
              AND principal_id = #{principalId}
              AND tenant_scope = #{tenantScope}
              AND message_id = #{messageId}
            LIMIT 1
            """)
    A2aMessageEntity findOwned(
            @Param("direction") String direction,
            @Param("principalId") long principalId,
            @Param("tenantScope") String tenantScope,
            @Param("messageId") String messageId);

    @Select("""
            SELECT * FROM (
                SELECT * FROM control_a2a_message
                WHERE direction = #{direction}
                  AND principal_id = #{principalId}
                  AND tenant_scope = #{tenantScope}
                  AND task_ref_id = #{taskRefId}
                ORDER BY created_at DESC, id DESC
                LIMIT #{limit}
            ) recent
            ORDER BY created_at ASC, id ASC
            """)
    List<A2aMessageEntity> findRecentForTask(
            @Param("direction") String direction,
            @Param("principalId") long principalId,
            @Param("tenantScope") String tenantScope,
            @Param("taskRefId") long taskRefId,
            @Param("limit") int limit);

    @Select("""
            SELECT * FROM control_a2a_message
            WHERE direction = #{direction}
              AND principal_id = #{principalId}
              AND tenant_scope = #{tenantScope}
              AND task_ref_id = #{taskRefId}
              AND role = #{role}
            ORDER BY created_at DESC, id DESC
            LIMIT 1
            """)
    A2aMessageEntity findLatestForTask(
            @Param("direction") String direction,
            @Param("principalId") long principalId,
            @Param("tenantScope") String tenantScope,
            @Param("taskRefId") long taskRefId,
            @Param("role") String role);
}
