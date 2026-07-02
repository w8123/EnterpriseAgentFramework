package com.enterprise.ai.domain.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("user_file_permission")
public class UserFilePermission {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 用户ID */
    private String userId;

    /** 文件业务ID */
    private String fileId;

    /** 权限类型: read / write / admin */
    private String permissionType;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
}
