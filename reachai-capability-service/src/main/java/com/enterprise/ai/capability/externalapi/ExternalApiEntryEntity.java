package com.enterprise.ai.capability.externalapi;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("capability_external_api_entry")
public class ExternalApiEntryEntity {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String entryKey;
    private Long providerId;
    private Long sourceId;
    private String title;
    private String summary;
    private String description;
    private String categoryCode;
    private String tagsJson;
    private String authType;
    private String pricingType;
    private Boolean httpsSupported;
    private String corsPolicy;
    private String docsUrl;
    private String termsUrl;
    private String sourceEntryKey;
    private String sourceCategory;
    private String publicationStatus;
    private String verificationStatus;
    private String specStatus;
    private Boolean featured;
    private Integer popularityScore;
    private LocalDateTime lastVerifiedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
