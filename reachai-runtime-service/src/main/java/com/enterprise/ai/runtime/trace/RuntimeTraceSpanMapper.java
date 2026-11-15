package com.enterprise.ai.runtime.trace;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface RuntimeTraceSpanMapper extends BaseMapper<RuntimeTraceSpanEntity> {
    @Select("SELECT * FROM runtime_trace_span WHERE id = #{id} FOR UPDATE")
    @Options(flushCache = Options.FlushCachePolicy.TRUE, useCache = false)
    RuntimeTraceSpanEntity selectByIdForUpdate(@Param("id") Long id);

    @Select("SELECT * FROM runtime_trace_span WHERE trace_id = #{traceId} AND span_id = #{spanId} FOR UPDATE")
    @Options(flushCache = Options.FlushCachePolicy.TRUE, useCache = false)
    RuntimeTraceSpanEntity selectByIdentityForUpdate(@Param("traceId") String traceId, @Param("spanId") String spanId);

    /** Successful capability calls the assistant reasoned its way to, newest first; names and outcomes only. */
    @Select("SELECT s.trace_id AS traceId, s.tool_name AS toolName, s.output_summary AS outputSummary, "
            + "s.started_at AS startedAt, r.latency_ms AS runLatencyMs "
            + "FROM runtime_trace_span s JOIN runtime_run r ON r.trace_id = s.trace_id "
            + "WHERE s.span_type = 'PROJECT_CAPABILITY' AND s.status = 'SUCCESS' AND s.agent_id = #{agentId} "
            + "AND s.tool_name <> 'find_business_capabilities' AND r.status = 'COMPLETED' "
            + "AND s.started_at >= #{since} ORDER BY s.id DESC LIMIT #{limit}")
    java.util.List<java.util.Map<String, Object>> selectCapabilityCalls(@Param("agentId") String agentId,
                                                                     @Param("since") java.time.LocalDateTime since,
                                                                     @Param("limit") int limit);
}
