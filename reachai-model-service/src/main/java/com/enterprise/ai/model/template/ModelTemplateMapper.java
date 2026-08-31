package com.enterprise.ai.model.template;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface ModelTemplateMapper extends BaseMapper<ModelTemplateEntity> {

    @Select("""
            SELECT *
            FROM model_template
            WHERE provider = #{provider}
              AND model_type = #{modelType}
              AND model_name = #{modelName}
            LIMIT 1
            """)
    ModelTemplateEntity findByCatalogKey(@Param("provider") String provider,
                                         @Param("modelType") String modelType,
                                         @Param("modelName") String modelName);

    @Select("""
            SELECT *
            FROM model_template
            WHERE provider = #{provider}
              AND model_name = #{modelName}
            ORDER BY id ASC
            LIMIT 2
            """)
    List<ModelTemplateEntity> findByProviderAndModelName(@Param("provider") String provider,
                                                         @Param("modelName") String modelName);

    @Insert("""
            INSERT IGNORE INTO model_template
                (id, name, provider, model_type, model_name, protocol,
                 connection_defaults_json, credential_schema_json, default_options_json,
                 params_schema_json, capabilities_json, source_key, lifecycle_status,
                 recommendation_status, recommendation_tier, recommendation_reason,
                 official_positioning, released_at, deprecated_at, retire_at,
                 replacement_model_name, last_seen_at, last_verified_at, source_url,
                 source_revision, sync_managed, icon_key, enabled, sort_order, remark,
                 created_at, updated_at)
            VALUES
                (#{id}, #{name}, #{provider}, #{modelType}, #{modelName}, #{protocol},
                 #{connectionDefaultsJson}, #{credentialSchemaJson}, #{defaultOptionsJson},
                 #{paramsSchemaJson}, #{capabilitiesJson}, #{sourceKey}, #{lifecycleStatus},
                 #{recommendationStatus}, #{recommendationTier}, #{recommendationReason},
                 #{officialPositioning}, #{releasedAt}, #{deprecatedAt}, #{retireAt},
                 #{replacementModelName}, #{lastSeenAt}, #{lastVerifiedAt}, #{sourceUrl},
                 #{sourceRevision}, #{syncManaged}, #{iconKey}, #{enabled}, #{sortOrder}, #{remark},
                 #{createdAt}, #{updatedAt})
            """)
    int insertIgnore(ModelTemplateEntity entity);
}
