package com.enterprise.ai.control.identity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("control_external_user_binding")
public class ExternalUserBindingEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String tenantId;

    private Long businessUserId;

    private String appId;

    private String externalUserId;

    private String externalUserName;

    private String deptId;

    private String deptName;

    private String status;

    private LocalDateTime lastSeenAt;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
