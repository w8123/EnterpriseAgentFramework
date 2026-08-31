package com.enterprise.ai.model.catalog;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface ModelCatalogSettingMapper {

    @Select("SELECT auto_sync_enabled FROM model_catalog_setting WHERE id = 1")
    Boolean selectAutoSyncEnabled();

    @Insert("""
            INSERT INTO model_catalog_setting (id, auto_sync_enabled, created_at, updated_at)
            VALUES (1, #{enabled}, NOW(), NOW())
            ON DUPLICATE KEY UPDATE
                auto_sync_enabled = VALUES(auto_sync_enabled),
                updated_at = NOW()
            """)
    int upsertAutoSyncEnabled(@Param("enabled") boolean enabled);
}
