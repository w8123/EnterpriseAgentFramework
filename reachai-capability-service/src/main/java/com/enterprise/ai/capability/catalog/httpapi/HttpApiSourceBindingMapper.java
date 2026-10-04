package com.enterprise.ai.capability.catalog.httpapi;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface HttpApiSourceBindingMapper extends BaseMapper<HttpApiSourceBindingEntity> {

    @Select("""
            SELECT *
            FROM capability_http_api_source_binding
            WHERE project_id = #{projectId}
              AND project_code = #{projectCode}
              AND environment = #{environment}
              AND source_kind = #{sourceKind}
              AND source_key = #{sourceKey}
            FOR UPDATE
            """)
    HttpApiSourceBindingEntity selectByScopeAndSourceForUpdate(@Param("projectId") Long projectId,
                                                               @Param("projectCode") String projectCode,
                                                               @Param("environment") String environment,
                                                               @Param("sourceKind") String sourceKind,
                                                               @Param("sourceKey") String sourceKey);
}
