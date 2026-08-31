package com.enterprise.ai.control.a2a.infrastructure.persistence;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface A2aTaskMapper extends BaseMapper<A2aTaskEntity> {

    @Select("""
            SELECT * FROM control_a2a_task
            WHERE direction = #{direction}
              AND principal_id = #{principalId}
              AND tenant_scope = #{tenantScope}
              AND task_id = #{taskId}
            LIMIT 1
            """)
    A2aTaskEntity findOwned(
            @Param("direction") String direction,
            @Param("principalId") long principalId,
            @Param("tenantScope") String tenantScope,
            @Param("taskId") String taskId);

    @Select("""
            SELECT * FROM control_a2a_task
            WHERE direction = #{direction}
              AND principal_id = #{principalId}
              AND tenant_scope = #{tenantScope}
              AND task_id = #{taskId}
            LIMIT 1
            FOR UPDATE
            """)
    A2aTaskEntity lockOwned(
            @Param("direction") String direction,
            @Param("principalId") long principalId,
            @Param("tenantScope") String tenantScope,
            @Param("taskId") String taskId);

    @Select("""
            SELECT * FROM control_a2a_task
            WHERE direction = #{direction}
              AND principal_id = #{principalId}
              AND tenant_scope = #{tenantScope}
              AND id = #{taskRefId}
            LIMIT 1
            """)
    A2aTaskEntity findOwnedByRefId(
            @Param("direction") String direction,
            @Param("principalId") long principalId,
            @Param("tenantScope") String tenantScope,
            @Param("taskRefId") long taskRefId);

    @Select("""
            SELECT * FROM control_a2a_task
            WHERE execution_id = #{executionId}
            LIMIT 1
            """)
    A2aTaskEntity findByExecutionId(@Param("executionId") String executionId);

    @Select("""
            SELECT * FROM control_a2a_task
            WHERE execution_id = #{executionId}
            LIMIT 1
            FOR UPDATE
            """)
    A2aTaskEntity lockByExecutionId(@Param("executionId") String executionId);

    @Select("""
            SELECT * FROM control_a2a_task
            WHERE id = #{taskRefId}
            LIMIT 1
            FOR UPDATE
            """)
    A2aTaskEntity lockByRefId(@Param("taskRefId") long taskRefId);

    @Select("""
            <script>
            SELECT * FROM control_a2a_task
            WHERE direction = #{direction}
              AND principal_id = #{principalId}
              AND tenant_scope = #{tenantScope}
              <if test="publicationId != null">AND publication_id = #{publicationId}</if>
              <if test="contextId != null">AND context_id = #{contextId}</if>
              <if test="state != null">AND state = #{state}</if>
              <if test="statusTimestampAfter != null">AND updated_at &gt;= #{statusTimestampAfter}</if>
            ORDER BY updated_at DESC, id DESC
            LIMIT #{limit} OFFSET #{offset}
            </script>
            """)
    List<A2aTaskEntity> findOwnedPage(
            @Param("direction") String direction,
            @Param("principalId") long principalId,
            @Param("tenantScope") String tenantScope,
            @Param("publicationId") Long publicationId,
            @Param("contextId") String contextId,
            @Param("state") String state,
            @Param("statusTimestampAfter") LocalDateTime statusTimestampAfter,
            @Param("limit") int limit,
            @Param("offset") int offset);

    @Select("""
            <script>
            SELECT COUNT(*) FROM control_a2a_task
            WHERE direction = #{direction}
              AND principal_id = #{principalId}
              AND tenant_scope = #{tenantScope}
              <if test="publicationId != null">AND publication_id = #{publicationId}</if>
              <if test="contextId != null">AND context_id = #{contextId}</if>
              <if test="state != null">AND state = #{state}</if>
              <if test="statusTimestampAfter != null">AND updated_at &gt;= #{statusTimestampAfter}</if>
            </script>
            """)
    long countOwned(
            @Param("direction") String direction,
            @Param("principalId") long principalId,
            @Param("tenantScope") String tenantScope,
            @Param("publicationId") Long publicationId,
            @Param("contextId") String contextId,
            @Param("state") String state,
            @Param("statusTimestampAfter") LocalDateTime statusTimestampAfter);

    @Select("""
            <script>
            SELECT COUNT(*) FROM control_a2a_task
            WHERE principal_id = #{principalId}
              <if test="publicationId != null">AND publication_id = #{publicationId}</if>
              AND state IN (
                'TASK_STATE_SUBMITTED', 'TASK_STATE_WORKING',
                'TASK_STATE_INPUT_REQUIRED', 'TASK_STATE_AUTH_REQUIRED'
              )
            </script>
            """)
    long countNonTerminal(
            @Param("principalId") long principalId,
            @Param("publicationId") Long publicationId);

    @Select("""
            SELECT COUNT(*) FROM control_a2a_task
            WHERE direction = 'OUTBOUND'
              AND principal_id = #{principalId}
              AND remote_agent_id = #{remoteAgentId}
              AND state IN (
                'TASK_STATE_SUBMITTED', 'TASK_STATE_WORKING',
                'TASK_STATE_INPUT_REQUIRED', 'TASK_STATE_AUTH_REQUIRED'
              )
            """)
    long countNonTerminalOutbound(
            @Param("principalId") long principalId,
            @Param("remoteAgentId") long remoteAgentId);

    @Select("""
            SELECT execution_id FROM control_a2a_task
            WHERE direction = 'INBOUND'
              AND state IN (
                'TASK_STATE_SUBMITTED', 'TASK_STATE_WORKING',
                'TASK_STATE_INPUT_REQUIRED', 'TASK_STATE_AUTH_REQUIRED'
            )
              AND deadline_at <= #{now}
            ORDER BY deadline_at ASC, id ASC
            LIMIT #{limit}
            """)
    List<String> findDueExecutionIds(
            @Param("now") LocalDateTime now,
            @Param("limit") int limit);

    @Select("""
            SELECT execution_id FROM control_a2a_task
            WHERE direction = 'OUTBOUND'
              AND state IN (
                'TASK_STATE_SUBMITTED', 'TASK_STATE_WORKING',
                'TASK_STATE_INPUT_REQUIRED', 'TASK_STATE_AUTH_REQUIRED'
            )
              AND deadline_at <= #{now}
            ORDER BY deadline_at ASC, id ASC
            LIMIT #{limit}
            """)
    List<String> findDueOutboundExecutionIds(
            @Param("now") LocalDateTime now,
            @Param("limit") int limit);
}
