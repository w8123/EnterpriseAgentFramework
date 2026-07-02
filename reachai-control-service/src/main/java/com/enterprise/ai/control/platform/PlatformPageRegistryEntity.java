package com.enterprise.ai.control.platform;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("eaf_page_registry")
public class PlatformPageRegistryEntity {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String projectCode;
    private String appId;
    private String pageKey;
    private String name;
    private String routePattern;
    private String origin;
    private String currentPageInstanceId;
    private String status;
    private LocalDateTime lastSeenAt;
    private String metadataJson;
}
