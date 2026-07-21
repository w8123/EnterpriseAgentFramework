package com.enterprise.ai.runtime.runops;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

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
}
