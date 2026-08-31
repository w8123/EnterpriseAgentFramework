package com.enterprise.ai.model.catalog;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface ModelCatalogSnapshotMapper extends BaseMapper<ModelCatalogSnapshotEntity> {

    @Insert("""
            INSERT INTO model_catalog_snapshot
                (source_id, source_url, content_sha256, content_type, content_size_bytes,
                 normalized_content, response_headers_json, analysis_json, parser_version,
                 fetched_at, analyzed_at, created_at)
            VALUES
                (#{sourceId}, #{sourceUrl}, #{contentSha256}, #{contentType}, #{contentSizeBytes},
                 #{normalizedContent}, #{responseHeadersJson}, #{analysisJson}, #{parserVersion},
                 #{fetchedAt}, #{analyzedAt}, #{createdAt})
            ON DUPLICATE KEY UPDATE id = LAST_INSERT_ID(id)
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insertOrResolve(ModelCatalogSnapshotEntity entity);

    @Update("""
            UPDATE model_catalog_snapshot
            SET analysis_json = #{analysisJson}, analyzed_at = NOW()
            WHERE id = #{snapshotId}
            """)
    int recordAnalysis(@Param("snapshotId") Long snapshotId,
                       @Param("analysisJson") String analysisJson);
}
