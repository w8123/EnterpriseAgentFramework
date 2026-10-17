package com.enterprise.ai.runtime.runops.consolehttpapi;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface RuntimeHttpApiProjectConnectionMapper extends BaseMapper<RuntimeHttpApiProjectConnectionEntity> {
    @Select("SELECT * FROM runtime_http_api_project_connection WHERE project_id = #{projectId} "
            + "AND environment = #{environment} FOR UPDATE")
    RuntimeHttpApiProjectConnectionEntity selectByScopeForUpdate(@Param("projectId") Long projectId,
                                                                 @Param("environment") String environment);
}
