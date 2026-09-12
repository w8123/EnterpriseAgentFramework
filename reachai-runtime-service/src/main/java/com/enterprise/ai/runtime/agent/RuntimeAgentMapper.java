package com.enterprise.ai.runtime.agent;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface RuntimeAgentMapper extends BaseMapper<RuntimeAgentEntity> {
    /** One bounded lookup; an alias belonging to another row must never shadow a stable Agent ID. */
    @Select("""
            SELECT id, project_id, project_code, key_slug, name, description, visibility,
                   allowed_roles_json, enabled, active_config_version_id, created_at, updated_at
            FROM runtime_agent
            WHERE id = #{lookup} OR key_slug = #{lookup}
            ORDER BY CASE WHEN id = #{lookup} THEN 0 ELSE 1 END
            LIMIT 1
            """)
    RuntimeAgentEntity selectByIdOrKeySlug(@Param("lookup") String lookup);

    /** Serialize draft creation and publication on the owning Agent, before reading its configs. */
    @Select("SELECT id FROM runtime_agent WHERE id = #{agentId} FOR UPDATE")
    @Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
    String lockById(@Param("agentId") String agentId);
}
