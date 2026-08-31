package com.enterprise.ai.model.catalog;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ModelCatalogChangeMapper extends BaseMapper<ModelCatalogChangeEntity> {

    @Insert("""
            INSERT IGNORE INTO model_catalog_change
                (run_id, snapshot_id, source_id, provider, model_name, model_type, change_type,
                 proposed_json, evidence_json, confidence, validation_status, publish_status,
                 published_template_id, review_reason, published_at, created_at, updated_at)
            VALUES
                (#{runId}, #{snapshotId}, #{sourceId}, #{provider}, #{modelName}, #{modelType}, #{changeType},
                 #{proposedJson}, #{evidenceJson}, #{confidence}, #{validationStatus}, #{publishStatus},
                 #{publishedTemplateId}, #{reviewReason}, #{publishedAt}, #{createdAt}, #{updatedAt})
            """)
    int insertIgnore(ModelCatalogChangeEntity entity);
}
