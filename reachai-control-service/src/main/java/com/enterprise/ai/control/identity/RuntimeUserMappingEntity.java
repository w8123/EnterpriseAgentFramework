package com.enterprise.ai.control.identity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("control_runtime_user_mapping")
public class RuntimeUserMappingEntity {

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

    /** Non-null only for the single active tenant/platform-user slot. */
    private Integer activeMarker;

    private String createdBy;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    private LocalDateTime deletedAt;
}
