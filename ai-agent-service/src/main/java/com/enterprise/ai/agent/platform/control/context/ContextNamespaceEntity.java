package com.enterprise.ai.agent.platform.control.context;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("context_namespace")
public class ContextNamespaceEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String namespaceKey;
    private String namespaceType;
    private String tenantId;
    private Long projectId;
    private String projectCode;
    private String ownerType;
    private String ownerId;
    private String displayName;
    private String description;
    private String status;
    private String createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private LocalDateTime deletedAt;
}
