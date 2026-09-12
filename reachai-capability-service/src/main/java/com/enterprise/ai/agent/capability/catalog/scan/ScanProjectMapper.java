package com.enterprise.ai.agent.capability.catalog.scan;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface ScanProjectMapper extends BaseMapper<ScanProjectEntity> {
    @Select("SELECT id FROM capability_scan_project WHERE id = #{projectId} FOR UPDATE")
    Long lockCapabilityChanges(@Param("projectId") Long projectId);
}
