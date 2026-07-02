package com.enterprise.ai.control.identity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("control_external_user_role_binding")
public class ExternalUserRoleBindingEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String tenantId;

    private Long businessUserId;

    private String appId;

    private String externalUserId;

    private String roleCode;

    private String roleName;

    private String source;

    private String status;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
