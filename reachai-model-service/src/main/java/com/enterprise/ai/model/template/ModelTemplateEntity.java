package com.enterprise.ai.model.template;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@TableName("model_template")
public class ModelTemplateEntity {

    @TableId(type = IdType.INPUT)
    private String id;

    private String name;

    private String provider;

    private String modelType;

    private String modelName;

    private String protocol;

    private String connectionDefaultsJson;

    private String credentialSchemaJson;

    private String defaultOptionsJson;

    private String paramsSchemaJson;

    private String capabilitiesJson;

    private String sourceKey;

    private String lifecycleStatus;

    private String recommendationStatus;

    private String recommendationTier;

    private String recommendationReason;

    private String officialPositioning;

    private LocalDate releasedAt;

    private LocalDate deprecatedAt;

    private LocalDate retireAt;

    private String replacementModelName;

    private LocalDateTime lastSeenAt;

    private LocalDateTime lastVerifiedAt;

    private String sourceUrl;

    private String sourceRevision;

    private Boolean syncManaged;

    private String iconKey;

    private Boolean enabled;

    private Integer sortOrder;

    private String remark;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
