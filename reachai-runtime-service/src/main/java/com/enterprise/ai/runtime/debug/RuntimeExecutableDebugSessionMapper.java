package com.enterprise.ai.runtime.debug;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface RuntimeExecutableDebugSessionMapper extends BaseMapper<RuntimeExecutableDebugSessionEntity> {
    @Select("SELECT * FROM runtime_executable_debug_session WHERE id = #{id} FOR UPDATE")
    @Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
    RuntimeExecutableDebugSessionEntity selectByIdForUpdate(@Param("id") String id);
}
