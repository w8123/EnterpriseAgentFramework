package com.enterprise.ai.runtime.workflow;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface RuntimeWorkflowDefinitionMapper extends BaseMapper<RuntimeWorkflowDefinitionEntity> {
    @Select("SELECT * FROM runtime_workflow WHERE id = #{id} FOR UPDATE")
    @Options(flushCache = Options.FlushCachePolicy.TRUE, useCache = false)
    RuntimeWorkflowDefinitionEntity selectForRelease(@Param("id") String id);
}
