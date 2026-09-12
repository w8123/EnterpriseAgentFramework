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
}
