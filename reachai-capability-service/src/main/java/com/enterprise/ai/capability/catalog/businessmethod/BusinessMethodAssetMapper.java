package com.enterprise.ai.capability.catalog.businessmethod;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Arg;
import org.apache.ibatis.annotations.ConstructorArgs;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface BusinessMethodAssetMapper extends BaseMapper<BusinessMethodAssetEntity> {
    @Select("""
            <script>
            SELECT COUNT(*) AS total,
                   COALESCE(SUM(CASE WHEN enabled = TRUE THEN 1 ELSE 0 END), 0) AS enabled,
                   COALESCE(SUM(CASE WHEN enabled = FALSE THEN 1 ELSE 0 END), 0) AS disabled
              FROM capability_business_method_asset
             WHERE accepted_revision_id IS NOT NULL AND status != 'UNACCEPTED'
            <if test="projectId != null">AND project_id = #{projectId}</if>
            </script>
            """)
    @ConstructorArgs({
            @Arg(column = "total", javaType = long.class),
            @Arg(column = "enabled", javaType = long.class),
            @Arg(column = "disabled", javaType = long.class)
    })
    BusinessMethodCatalogSummary summarize(@Param("projectId") Long projectId);
}
