package com.enterprise.ai.runtime.runops;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface RuntimeRunMapper extends BaseMapper<RuntimeRunEntity> {

    @Select("""
            SELECT
              GREATEST(COALESCE(r.tool_call_count, 0),
                (SELECT COUNT(*) FROM runtime_tool_call_log WHERE trace_id = #{traceId})) AS toolCallCount,
              GREATEST(COALESCE(r.guard_deny_count, 0), (SELECT COUNT(*) FROM runtime_guard_decision_log
                WHERE trace_id = #{traceId} AND decision = 'DENY')) AS guardDenyCount,
              GREATEST(COALESCE(r.approval_count, 0), (SELECT COUNT(*) FROM runtime_guard_decision_log
                WHERE trace_id = #{traceId} AND decision = 'REQUIRE_CONFIRMATION')) AS approvalCount,
              r.metadata_json AS metadataJson
            FROM runtime_run r WHERE r.trace_id = #{traceId}
            """)
    RuntimeRunFinishCounts selectFinishCounts(@Param("traceId") String traceId);

    @Select("SELECT * FROM runtime_run WHERE trace_id = #{traceId} FOR UPDATE")
    @Options(flushCache = Options.FlushCachePolicy.TRUE, useCache = false)
    RuntimeRunEntity selectManagedProjectionForUpdate(@Param("traceId") String traceId);

    @Select("""
            <script>
            SELECT r.session_id AS sessionId,
                   COUNT(*) AS runCount,
                   SUM(CASE WHEN r.status IN ('FAILED', 'TIMED_OUT') THEN 1 ELSE 0 END) AS failedCount,
                   SUM(CASE WHEN r.status IN ('RUNNING', 'SUSPENDED') THEN 1 ELSE 0 END) AS openCount,
                   MAX(r.started_at) AS lastStartedAt
            FROM runtime_run r
            WHERE r.run_type = 'AGENT'
              AND r.started_at &gt;= #{since}
              AND r.session_id IN
              <foreach collection="sessionIds" item="sessionId" open="(" separator="," close=")">#{sessionId}</foreach>
            GROUP BY r.session_id
            </script>
            """)
    List<RuntimeSessionRunStatsView> selectSessionRunStats(@Param("sessionIds") List<String> sessionIds,
                                                           @Param("since") LocalDateTime since);

    /** 嵌入式对话（业务系统里的 AI 对话）按人汇总，只含计数，不含对话正文。 */
    @Select("""
            <script>
            SELECT r.app_id AS appId,
                   MAX(r.project_code) AS projectCode,
                   r.external_user_id AS externalUserId,
                   MAX(r.global_user_id) AS globalUserId,
                   COUNT(*) AS runCount,
                   COUNT(DISTINCT r.session_id) AS sessionCount,
                   SUM(CASE WHEN r.status IN ('FAILED', 'TIMED_OUT') THEN 1 ELSE 0 END) AS failedCount,
                   COALESCE(SUM(r.token_cost), 0) AS tokenCost,
                   MAX(r.started_at) AS lastStartedAt
            FROM runtime_run r
            WHERE r.entry_type = 'EMBED'
              AND r.run_type = 'AGENT'
              AND r.started_at &gt;= #{since}
              AND r.external_user_id IS NOT NULL
              <if test="projectCode != null">AND r.project_code = #{projectCode}</if>
            GROUP BY r.app_id, r.external_user_id
            ORDER BY runCount DESC
            LIMIT 500
            </script>
            """)
    List<RuntimeEmbedUserUsageView> selectEmbedUserUsage(@Param("since") LocalDateTime since,
                                                         @Param("projectCode") String projectCode);
}
