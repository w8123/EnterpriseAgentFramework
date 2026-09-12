package com.enterprise.ai.runtime.runops;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

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
}
