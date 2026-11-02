package com.enterprise.ai.control.memory;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** An owner-defined group inside the personal layer or one project layer. */
@Data
@TableName("control_personal_memory_group")
public class PersonalMemoryGroupEntity {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String tenantId;
    private String ownerId;
    private String ownerDomain;
    private String scopeKey;
    private String projectCode;
    /** Display snapshot of the project name; the code is the identity. */
    private String projectName;
    private String name;
    /** PREFERENCE groups are always handed to the AI; NOTE groups only when relevant. */
    private String kind;
    private Integer sortOrder;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
