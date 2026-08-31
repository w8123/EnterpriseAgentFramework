package com.enterprise.ai.model.catalog;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("model_catalog_snapshot")
public class ModelCatalogSnapshotEntity {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long sourceId;
    private String sourceUrl;
    private String contentSha256;
    private String contentType;
    private Integer contentSizeBytes;
    private String normalizedContent;
    private String responseHeadersJson;
    private String analysisJson;
    private String parserVersion;
    private LocalDateTime fetchedAt;
    private LocalDateTime analyzedAt;
    private LocalDateTime createdAt;
}
