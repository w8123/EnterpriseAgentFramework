package com.enterprise.ai.capability.externalapi;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("capability_external_api_source")
public class ExternalApiSourceEntity {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String sourceKey;
    private String name;
    private String sourceType;
    private String sourceUrl;
    private String homepageUrl;
    private String description;
    private String syncStrategy;
    private String trustLevel;
    private String status;
    private LocalDateTime lastSyncedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
