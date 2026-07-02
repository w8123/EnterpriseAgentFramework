package com.enterprise.ai.runtime.workflow;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("ai_agent")
public class RuntimeAgentEntryEntity {

    @TableId(type = IdType.INPUT)
    private String id;

    private Long projectId;

    private String projectCode;

    private String keySlug;

    private String name;

    private String description;

    private String agentKind;

    private String visibility;

    private String systemPrompt;

    private String modelInstanceId;

    private String allowedRolesJson;

    private String entryConfigJson;

    private Boolean enabled;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
