package com.enterprise.ai.runtime.runops;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

@Mapper
public interface RuntimeRunMapper extends BaseMapper<RuntimeRunEntity> {

    @Select("""
            SELECT
              (SELECT COUNT(*) FROM runtime_tool_call_log WHERE trace_id = #{traceId}) AS toolCallCount,
              (SELECT COUNT(*) FROM runtime_guard_decision_log
                WHERE trace_id = #{traceId} AND decision = 'DENY') AS guardDenyCount,
              (SELECT COUNT(*) FROM runtime_guard_decision_log
                WHERE trace_id = #{traceId} AND decision = 'REQUIRE_CONFIRMATION') AS approvalCount
            """)
    RuntimeRunFinishCounts selectFinishCounts(@Param("traceId") String traceId);

    @Update("""
            UPDATE runtime_run
            SET status = #{status},
                suspension_reason = #{suspensionReason},
                output_summary = #{outputSummary},
                error_code = #{errorCode},
                error_message = #{errorMessage},
                latency_ms = #{latencyMs},
                approval_count = #{approvalCount},
                metadata_json = #{metadataJson},
                ended_at = #{endedAt},
                updated_at = #{updatedAt}
            WHERE trace_id = #{traceId}
            """)
    int updateManagedExecutionProjection(
            @Param("traceId") String traceId,
            @Param("status") String status,
            @Param("suspensionReason") String suspensionReason,
            @Param("outputSummary") String outputSummary,
            @Param("errorCode") String errorCode,
            @Param("errorMessage") String errorMessage,
            @Param("latencyMs") Integer latencyMs,
            @Param("approvalCount") int approvalCount,
            @Param("metadataJson") String metadataJson,
            @Param("endedAt") LocalDateTime endedAt,
            @Param("updatedAt") LocalDateTime updatedAt);
}
