package com.enterprise.ai.model.catalog;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("model_catalog_source")
public class ModelCatalogSourceEntity {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String sourceKey;
    private String provider;
    private String name;
    private String sourceKind;
    private String sourceUrl;
    private String allowedHost;
    private String contentFormat;
    private String authType;
    private String trustLevel;
    private Boolean enabled;
    private Integer sortOrder;
    private String lastHttpEtag;
    private String lastHttpModified;
    private String lastContentSha256;
    private LocalDateTime lastCheckedAt;
    private LocalDateTime lastSuccessAt;
    private String lastErrorCode;
    private String lastErrorMessage;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
