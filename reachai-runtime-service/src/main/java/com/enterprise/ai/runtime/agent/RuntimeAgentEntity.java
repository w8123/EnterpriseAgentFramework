package com.enterprise.ai.runtime.agent;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("runtime_agent")
public class RuntimeAgentEntity {

    @TableId(type = IdType.INPUT)
    private String id;

    private Long projectId;

    private String projectCode;

    private String keySlug;

    private String name;

    private String description;

    private String visibility;

    private Long activeConfigVersionId;

    private String allowedRolesJson;

    private Boolean enabled;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
