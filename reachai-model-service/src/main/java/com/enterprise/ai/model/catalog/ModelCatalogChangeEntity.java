package com.enterprise.ai.model.catalog;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("model_catalog_change")
public class ModelCatalogChangeEntity {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long runId;
    private Long snapshotId;
    private Long sourceId;
    private String provider;
    private String modelName;
    private String modelType;
    private String changeType;
    private String proposedJson;
    private String evidenceJson;
    private BigDecimal confidence;
    private String validationStatus;
    private String publishStatus;
    private String publishedTemplateId;
    private String reviewReason;
    private LocalDateTime publishedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
