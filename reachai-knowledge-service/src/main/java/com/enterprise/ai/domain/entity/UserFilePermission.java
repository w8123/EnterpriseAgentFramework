package com.enterprise.ai.domain.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("knowledge_user_file_permission")
public class UserFilePermission {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 每次授予生成的记录身份；撤销后重新授予不得复用。 */
    @JsonIgnore
    @TableField(fill = FieldFill.INSERT, updateStrategy = FieldStrategy.NEVER)
    private String recordGeneration;

    /** 用户ID */
    private String userId;

    /** 文件业务ID */
    private String fileId;

    /** 权限类型: read / write / admin */
    private String permissionType;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
}
