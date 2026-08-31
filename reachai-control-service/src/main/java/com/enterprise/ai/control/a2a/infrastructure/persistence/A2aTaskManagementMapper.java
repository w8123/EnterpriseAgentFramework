package com.enterprise.ai.control.a2a.infrastructure.persistence;

import com.enterprise.ai.control.a2a.application.port.A2aTaskManagementReader.Query;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface A2aTaskManagementMapper {

    String SELECT_COLUMNS = """
            SELECT t.id AS task_ref_id, t.task_id, t.direction, t.state, t.context_id,
                   t.publication_id, pub.publication_key, t.publication_revision_id,
                   t.remote_agent_id, remote.remote_agent_key, t.remote_revision_id,
                   t.principal_id, principal.principal_key,
                   principal.display_name AS principal_display_name,
                   t.tenant_scope, principal.trust_profile_id, trust.profile_key AS trust_profile_key,
                   t.execution_id, t.runtime_run_id, t.trace_id, t.runtime_interaction_id,
                   t.status_message_summary AS status_summary, t.error_code, t.error_summary,
                   t.cancel_phase,
                   outbound.poll_status AS outbound_poll_status,
                   outbound.poll_attempt_count AS outbound_poll_attempt_count,
                   outbound.next_poll_at AS outbound_next_poll_at,
                   outbound.last_polled_at AS outbound_last_polled_at,
                   outbound.last_poll_error_code AS outbound_last_poll_error_code,
                   outbound.last_poll_error_summary AS outbound_last_poll_error_summary,
                   t.attempt_count, t.last_event_sequence,
                   t.submitted_at, t.started_at, t.completed_at, t.deadline_at,
                   t.retention_expires_at, t.updated_at
            FROM control_a2a_task t
            LEFT JOIN control_a2a_publication pub ON pub.id = t.publication_id
            LEFT JOIN control_a2a_remote_agent remote ON remote.id = t.remote_agent_id
            LEFT JOIN control_a2a_principal principal ON principal.id = t.principal_id
            LEFT JOIN control_a2a_trust_profile trust ON trust.id = principal.trust_profile_id
            LEFT JOIN control_a2a_outbound_execution outbound ON outbound.task_ref_id = t.id
            """;

    @Select("<script>" + SELECT_COLUMNS + """
            WHERE 1 = 1
              <if test="query.search != null and query.search != ''">
                AND (t.task_id LIKE CONCAT('%', #{query.search}, '%')
                  OR t.context_id LIKE CONCAT('%', #{query.search}, '%')
                  OR t.trace_id LIKE CONCAT('%', #{query.search}, '%')
                  OR t.runtime_run_id LIKE CONCAT('%', #{query.search}, '%')
                  OR principal.principal_key LIKE CONCAT('%', #{query.search}, '%')
                  OR pub.publication_key LIKE CONCAT('%', #{query.search}, '%')
                  OR remote.remote_agent_key LIKE CONCAT('%', #{query.search}, '%'))
              </if>
              <if test="query.direction != null">AND t.direction = #{query.direction}</if>
              <if test="query.state != null">AND t.state = #{query.state}</if>
              <if test="query.publicationId != null">AND t.publication_id = #{query.publicationId}</if>
              <if test="query.remoteAgentId != null">AND t.remote_agent_id = #{query.remoteAgentId}</if>
              <if test="query.principalId != null">AND t.principal_id = #{query.principalId}</if>
              <if test="query.tenantScope != null">AND t.tenant_scope = #{query.tenantScope}</if>
              <if test="query.submittedAfter != null">AND t.submitted_at &gt;= #{query.submittedAfter}</if>
              <if test="query.submittedBefore != null">AND t.submitted_at &lt; #{query.submittedBefore}</if>
            ORDER BY t.updated_at DESC, t.id DESC
            LIMIT #{query.limit} OFFSET #{query.offset}
            </script>
            """)
    List<A2aTaskManagementRow> findPage(@Param("query") Query query);

    @Select("""
            <script>
            SELECT COUNT(*)
            FROM control_a2a_task t
            LEFT JOIN control_a2a_publication pub ON pub.id = t.publication_id
            LEFT JOIN control_a2a_remote_agent remote ON remote.id = t.remote_agent_id
            LEFT JOIN control_a2a_principal principal ON principal.id = t.principal_id
            WHERE 1 = 1
              <if test="query.search != null and query.search != ''">
                AND (t.task_id LIKE CONCAT('%', #{query.search}, '%')
                  OR t.context_id LIKE CONCAT('%', #{query.search}, '%')
                  OR t.trace_id LIKE CONCAT('%', #{query.search}, '%')
                  OR t.runtime_run_id LIKE CONCAT('%', #{query.search}, '%')
                  OR principal.principal_key LIKE CONCAT('%', #{query.search}, '%')
                  OR pub.publication_key LIKE CONCAT('%', #{query.search}, '%')
                  OR remote.remote_agent_key LIKE CONCAT('%', #{query.search}, '%'))
              </if>
              <if test="query.direction != null">AND t.direction = #{query.direction}</if>
              <if test="query.state != null">AND t.state = #{query.state}</if>
              <if test="query.publicationId != null">AND t.publication_id = #{query.publicationId}</if>
              <if test="query.remoteAgentId != null">AND t.remote_agent_id = #{query.remoteAgentId}</if>
              <if test="query.principalId != null">AND t.principal_id = #{query.principalId}</if>
              <if test="query.tenantScope != null">AND t.tenant_scope = #{query.tenantScope}</if>
              <if test="query.submittedAfter != null">AND t.submitted_at &gt;= #{query.submittedAfter}</if>
              <if test="query.submittedBefore != null">AND t.submitted_at &lt; #{query.submittedBefore}</if>
            </script>
            """)
    long countPage(@Param("query") Query query);

    @Select("<script>" + SELECT_COLUMNS + """
            WHERE t.task_id = #{taskId}
              <if test="direction != null">AND t.direction = #{direction}</if>
            ORDER BY t.id ASC
            LIMIT 2
            </script>
            """)
    List<A2aTaskManagementRow> findByTaskId(
            @Param("taskId") String taskId, @Param("direction") String direction);

    @Select("""
            SELECT * FROM control_a2a_task_event
            WHERE task_ref_id = #{taskRefId}
            ORDER BY sequence_no ASC
            """)
    List<A2aTaskEventEntity> findEvents(@Param("taskRefId") long taskRefId);

    @Select("""
            SELECT * FROM control_a2a_message
            WHERE task_ref_id = #{taskRefId}
            ORDER BY created_at ASC, id ASC
            """)
    List<A2aMessageEntity> findMessages(@Param("taskRefId") long taskRefId);

    @Select("""
            SELECT * FROM control_a2a_artifact
            WHERE task_ref_id = #{taskRefId}
            ORDER BY created_at ASC, id ASC
            """)
    List<A2aArtifactEntity> findArtifacts(@Param("taskRefId") long taskRefId);
}
