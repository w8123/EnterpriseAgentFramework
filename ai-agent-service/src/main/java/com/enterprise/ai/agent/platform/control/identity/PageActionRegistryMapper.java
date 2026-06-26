package com.enterprise.ai.agent.platform.control.identity;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface PageActionRegistryMapper extends BaseMapper<PageActionRegistryEntity> {

    @Insert("""
            INSERT INTO eaf_page_action_registry (
                project_code, app_id, page_key, action_key, title, description,
                confirm_required, input_schema_json, output_schema_json, sample_args_json,
                allowed_agent_ids_json, metadata_json, status, last_seen_at, created_at, updated_at
            ) VALUES (
                #{projectCode}, #{appId}, #{pageKey}, #{actionKey}, #{title}, #{description},
                #{confirmRequired}, #{inputSchemaJson}, #{outputSchemaJson}, #{sampleArgsJson},
                #{allowedAgentIdsJson}, #{metadataJson}, #{status}, #{lastSeenAt}, #{createdAt}, #{updatedAt}
            )
            ON DUPLICATE KEY UPDATE
                title = VALUES(title),
                description = VALUES(description),
                confirm_required = VALUES(confirm_required),
                input_schema_json = VALUES(input_schema_json),
                output_schema_json = VALUES(output_schema_json),
                sample_args_json = VALUES(sample_args_json),
                allowed_agent_ids_json = VALUES(allowed_agent_ids_json),
                metadata_json = VALUES(metadata_json),
                status = VALUES(status),
                last_seen_at = VALUES(last_seen_at),
                updated_at = VALUES(updated_at)
            """)
    int upsert(PageActionRegistryEntity entity);

    @Update("""
            <script>
            UPDATE eaf_page_action_registry
            SET status = 'REMOVED', updated_at = NOW()
            WHERE project_code = #{projectCode}
              AND app_id = #{appId}
              AND page_key = #{pageKey}
              AND status != 'REMOVED'
            <if test="activeActionKeys != null and activeActionKeys.size() > 0">
              AND action_key NOT IN
              <foreach collection="activeActionKeys" item="key" open="(" separator="," close=")">
                #{key}
              </foreach>
            </if>
            </script>
            """)
    int markMissingRemoved(@Param("projectCode") String projectCode,
                           @Param("appId") String appId,
                           @Param("pageKey") String pageKey,
                           @Param("activeActionKeys") List<String> activeActionKeys);
}
