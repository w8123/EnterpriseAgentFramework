package com.enterprise.ai.runtime.runops.consolehttpapi;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface RuntimeHttpApiConnectionMapper extends BaseMapper<RuntimeHttpApiConnectionEntity> {
    @Select("SELECT * FROM runtime_http_api_connection WHERE qualified_name = #{qualifiedName} FOR UPDATE")
    RuntimeHttpApiConnectionEntity selectByRefForUpdate(@Param("qualifiedName") String qualifiedName);
}
