package com.enterprise.ai.control.governance;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("tool_acl")
public class ControlToolAclEntity {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String roleCode;
    private Long projectId;
    private String projectCode;
    private String targetKind;
    private String targetName;
    private String permission;
    private String note;
    private Boolean enabled;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
