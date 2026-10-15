package com.enterprise.ai.runtime.runops.consolecapability;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface ConsoleCapabilityInvocationMapper extends BaseMapper<ConsoleCapabilityInvocationEntity> {

    @Select("SELECT * FROM runtime_console_capability_invocation WHERE invocation_id = #{invocationId} LIMIT 1")
    ConsoleCapabilityInvocationEntity selectByInvocationId(@Param("invocationId") String invocationId);

    @Select("SELECT * FROM runtime_console_capability_invocation WHERE invocation_id = #{invocationId} FOR UPDATE")
    @Options(flushCache = Options.FlushCachePolicy.TRUE, useCache = false)
    ConsoleCapabilityInvocationEntity selectByInvocationIdForUpdate(@Param("invocationId") String invocationId);

}
