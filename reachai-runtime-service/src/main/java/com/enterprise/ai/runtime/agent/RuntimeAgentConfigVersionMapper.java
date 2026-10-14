package com.enterprise.ai.runtime.agent;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface RuntimeAgentConfigVersionMapper extends BaseMapper<RuntimeAgentConfigVersionEntity> {
    /** Full external-pin coverage without hydrating unrelated configuration JSON or Agent rows. */
    @Select("""
            <script>
            SELECT COUNT(c.id) existing_count,
                   COALESCE(SUM(CASE WHEN a.id IS NULL THEN 1 ELSE 0 END), 0) missing_owner_count
            FROM runtime_agent_config_version c LEFT JOIN runtime_agent a ON a.id=c.agent_id
            WHERE c.id IN <foreach collection="ids" item="id" open="(" separator="," close=")">#{id}</foreach>
            </script>
            """)
    PinCoverage inspectPinnedCoverage(@Param("ids") java.util.Collection<Long> ids);

    @lombok.Data
    class PinCoverage {
        private long existingCount;
        private long missingOwnerCount;
    }
}
