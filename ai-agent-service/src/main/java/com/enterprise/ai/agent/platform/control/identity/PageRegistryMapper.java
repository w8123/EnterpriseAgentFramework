package com.enterprise.ai.agent.platform.control.identity;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface PageRegistryMapper extends BaseMapper<PageRegistryEntity> {

    @Insert("""
            INSERT INTO eaf_page_registry (
                project_code, app_id, page_key, name, route_pattern, origin,
                current_page_instance_id, status, last_seen_at, metadata_json,
                created_at, updated_at
            ) VALUES (
                #{projectCode}, #{appId}, #{pageKey}, #{name}, #{routePattern}, #{origin},
                #{currentPageInstanceId}, #{status}, #{lastSeenAt}, #{metadataJson},
                #{createdAt}, #{updatedAt}
            )
            ON DUPLICATE KEY UPDATE
                app_id = VALUES(app_id),
                name = VALUES(name),
                route_pattern = VALUES(route_pattern),
                current_page_instance_id = VALUES(current_page_instance_id),
                status = VALUES(status),
                last_seen_at = VALUES(last_seen_at),
                metadata_json = VALUES(metadata_json),
                updated_at = VALUES(updated_at)
            """)
    int upsert(PageRegistryEntity entity);
}
