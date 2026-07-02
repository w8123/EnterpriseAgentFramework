package com.enterprise.ai.control.identity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("platform_user_role")
public class PlatformUserRoleEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    private Long roleId;

    private String scopeType;

    private String scopeValue;

    private LocalDateTime createdAt;
}
