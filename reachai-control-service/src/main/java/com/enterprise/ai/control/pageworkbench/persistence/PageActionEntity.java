package com.enterprise.ai.control.pageworkbench.persistence;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("control_page_action")
public class PageActionEntity {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long pageId;
    private Long projectId;
    private String projectCode;
    private String pageKey;
    private String actionKey;
    private String title;
    private String description;
    private String actionType;
    private String riskLevel;
    private Boolean confirmRequired;
    private String permissionKey;
    private String inputSchemaJson;
    private String outputSchemaJson;
    private String sampleArgsJson;
    private String allowedAgentIdsJson;
    private String implementationRef;
    private String sourceType;
    private String status;
    private String metadataJson;
    private LocalDateTime lastVerifiedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
