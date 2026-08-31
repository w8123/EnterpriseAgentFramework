package com.enterprise.ai.model.template;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Map;

@Data
@Builder
public class ModelTemplateResponse {
    private String id;
    private String name;
    private String provider;
    private String modelType;
    private String modelName;
    private String protocol;
    private Map<String, Object> connectionDefaults;
    private Object credentialSchema;
    private Map<String, Object> defaultOptions;
    private Object paramsSchema;
    private Object capabilities;
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
