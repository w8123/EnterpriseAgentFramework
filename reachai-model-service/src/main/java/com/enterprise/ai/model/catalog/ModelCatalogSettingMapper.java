package com.enterprise.ai.model.catalog;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface ModelCatalogSettingMapper {

    @Select("SELECT auto_sync_enabled FROM model_catalog_setting WHERE id = 1")
    Boolean selectAutoSyncEnabled();

    @Select("SELECT analyzer_model_instance_id FROM model_catalog_setting WHERE id = 1")
    String selectAnalyzerModelInstanceId();

    @Insert("""
            INSERT INTO model_catalog_setting (id, auto_sync_enabled, created_at, updated_at)
            VALUES (1, #{enabled}, NOW(), NOW())
            ON DUPLICATE KEY UPDATE
                auto_sync_enabled = VALUES(auto_sync_enabled),
                updated_at = NOW()
            """)
    int upsertAutoSyncEnabled(@Param("enabled") boolean enabled);

    @Insert("""
            INSERT INTO model_catalog_setting (id, auto_sync_enabled, analyzer_model_instance_id, created_at, updated_at)
            VALUES (1, 0, #{instanceId}, NOW(), NOW())
            ON DUPLICATE KEY UPDATE
                analyzer_model_instance_id = VALUES(analyzer_model_instance_id),
                updated_at = NOW()
            """)
    int upsertAnalyzerModelInstanceId(@Param("instanceId") String instanceId);
}
