package com.enterprise.ai.capability.externalapi;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("capability_external_api_provider")
public class ExternalApiProviderEntity {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String providerKey;
    private String name;
    private String homepageUrl;
    private String logoUrl;
    private Boolean verified;
    private String status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
