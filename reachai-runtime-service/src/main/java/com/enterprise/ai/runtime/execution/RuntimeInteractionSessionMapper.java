package com.enterprise.ai.runtime.execution;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface RuntimeInteractionSessionMapper extends BaseMapper<RuntimeInteractionSessionEntity> {
    @Select("SELECT * FROM runtime_interaction_session WHERE id = #{id} FOR UPDATE")
    @Options(flushCache = Options.FlushCachePolicy.TRUE, useCache = false)
    RuntimeInteractionSessionEntity selectForUpdate(@Param("id") String id);
}
