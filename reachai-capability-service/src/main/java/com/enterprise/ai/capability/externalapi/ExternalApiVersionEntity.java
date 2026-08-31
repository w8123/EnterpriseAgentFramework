package com.enterprise.ai.capability.externalapi;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("capability_external_api_version")
public class ExternalApiVersionEntity {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long entryId;
    private String versionKey;
    private String baseUrl;
    private String openapiUrl;
    private String specHash;
    private String rawMetadataJson;
    private String publicationStatus;
    private LocalDateTime publishedAt;
    private LocalDateTime deprecatedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
