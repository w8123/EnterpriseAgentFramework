package com.enterprise.ai.control.context;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("control_context_runtime_user_mapping")
public class ContextRuntimeUserMappingEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String tenantId;

    private Long platformUserId;

    private String runtimeUserId;

    private String globalUserId;

    private String externalUserId;

    private Long projectId;

    private String projectCode;

    private String status;

    private String createdBy;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    private LocalDateTime deletedAt;
}
