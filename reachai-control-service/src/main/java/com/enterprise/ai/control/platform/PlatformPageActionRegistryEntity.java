package com.enterprise.ai.control.platform;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("eaf_page_action_registry")
public class PlatformPageActionRegistryEntity {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String projectCode;
    private String appId;
    private String pageKey;
    private String actionKey;
    private String title;
    private String description;
    private Boolean confirmRequired;
    private String inputSchemaJson;
    private String outputSchemaJson;
    private String sampleArgsJson;
    private String allowedAgentIdsJson;
    private String metadataJson;
    private String status;
    private LocalDateTime lastSeenAt;
}
