package com.enterprise.ai.runtime.trace.usage;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface RuntimeModelUsageMapper extends BaseMapper<RuntimeModelUsageEntity> {

    /**
     * Token totals grouped by day, person, system, entry and model. Calls outside a run keep a NULL entry and
     * person, and are left out when a system filter is given (they belong to no system).
     */
    @Select("""
            <script>
            SELECT DATE(u.created_at) AS day,
                   r.app_id AS appId, r.external_user_id AS externalUserId, r.user_id AS userId,
                   r.project_code AS projectCode,
                   COALESCE(r.entry_type, u.usage_source) AS entryType,
                   u.model_instance_id AS modelInstanceId,
                   COUNT(*) AS calls,
                   COALESCE(SUM(u.input_tokens), 0) AS inputTokens,
                   COALESCE(SUM(u.output_tokens), 0) AS outputTokens,
                   COUNT(DISTINCT u.trace_id) AS runs
            FROM runtime_model_usage u
            LEFT JOIN runtime_run r ON r.trace_id = u.trace_id
            WHERE u.created_at &gt;= #{since}
              <if test="projectCode != null">AND r.project_code = #{projectCode}</if>
            GROUP BY DATE(u.created_at), r.app_id, r.external_user_id, r.user_id, r.project_code,
                     COALESCE(r.entry_type, u.usage_source), u.model_instance_id
            ORDER BY day
            LIMIT 20000
            </script>
            """)
    List<RuntimeModelUsageRow> selectUsage(@Param("since") LocalDateTime since, @Param("projectCode") String projectCode);

    /**
     * Runs for the audit export (no prompts or answers): who ran what, from which entry, with what result.
     * Build/debug entries are left out (not business use), and so are MCP runs: Control already lists each
     * employee AI-tool call with its run id.
     */
    @Select("""
            <script>
            SELECT r.trace_id AS traceId, r.run_type AS runType, r.entry_type AS entryType, r.status AS status,
                   r.project_code AS projectCode, r.app_id AS appId, r.external_user_id AS externalUserId,
                   r.user_id AS userId, r.agent_name AS agentName, r.workflow_name AS workflowName,
                   r.error_code AS errorCode, r.tool_call_count AS toolCallCount, r.approval_count AS approvalCount,
                   r.token_cost AS tokenCost, r.started_at AS startedAt, r.ended_at AS endedAt
            FROM runtime_run r
            WHERE r.started_at &gt;= #{since}
              AND r.entry_type IN ('EMBED', 'AUTOMATION', 'API', 'GATEWAY', 'A2A', 'AI_CODING_TASK')
              <if test="projectCode != null">AND r.project_code = #{projectCode}</if>
            ORDER BY r.started_at DESC, r.id DESC
            LIMIT #{limit}
            </script>
            """)
    List<RuntimeAuditRunRow> selectAuditRuns(@Param("since") LocalDateTime since,
                                             @Param("projectCode") String projectCode,
                                             @Param("limit") int limit);
}
