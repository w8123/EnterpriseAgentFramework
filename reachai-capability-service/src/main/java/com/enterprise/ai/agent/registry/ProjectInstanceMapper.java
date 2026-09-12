package com.enterprise.ai.agent.registry;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

@Mapper
public interface ProjectInstanceMapper extends BaseMapper<ProjectInstanceEntity> {
    @Insert("""
            INSERT INTO capability_project_instance
                (project_id, project_code, instance_id, base_url, host, port, app_version, sdk_version,
                 status, metadata_json, last_heartbeat_at, created_at, updated_at)
            VALUES (#{instance.projectId}, #{instance.projectCode}, #{instance.instanceId}, #{instance.baseUrl},
                    #{instance.host}, #{instance.port}, #{instance.appVersion}, #{instance.sdkVersion},
                    'ONLINE', #{instance.metadataJson}, #{instance.lastHeartbeatAt}, #{instance.createdAt}, #{instance.updatedAt})
            ON DUPLICATE KEY UPDATE
                base_url = VALUES(base_url), host = VALUES(host), port = VALUES(port),
                app_version = VALUES(app_version), sdk_version = VALUES(sdk_version),
                metadata_json = VALUES(metadata_json),
                status = CASE WHEN UPPER(status) = 'DISABLED' THEN 'DISABLED' ELSE 'ONLINE' END,
                last_heartbeat_at = GREATEST(COALESCE(last_heartbeat_at, VALUES(last_heartbeat_at)), VALUES(last_heartbeat_at)),
                updated_at = GREATEST(COALESCE(updated_at, VALUES(updated_at)), VALUES(updated_at))
            """)
    int upsertHeartbeat(@Param("instance") ProjectInstanceEntity instance);

    @Update("""
            UPDATE capability_project_instance
            SET status = 'OFFLINE',
                last_heartbeat_at = GREATEST(COALESCE(last_heartbeat_at, #{observedAt}), #{observedAt}),
                updated_at = GREATEST(COALESCE(updated_at, #{observedAt}), #{observedAt})
            WHERE project_code = #{projectCode} AND instance_id = #{instanceId} AND UPPER(status) <> 'DISABLED'
            """)
    int markOffline(@Param("projectCode") String projectCode, @Param("instanceId") String instanceId,
                    @Param("observedAt") LocalDateTime observedAt);

    @Update("""
            UPDATE capability_project_instance SET status = #{status},
                updated_at = GREATEST(COALESCE(updated_at, #{updatedAt}), #{updatedAt})
            WHERE project_code = #{projectCode} AND instance_id = #{instanceId}
            """)
    int updateStatus(@Param("projectCode") String projectCode, @Param("instanceId") String instanceId,
                     @Param("status") String status, @Param("updatedAt") LocalDateTime updatedAt);
}
