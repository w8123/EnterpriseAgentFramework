package com.enterprise.ai.runtime.workflow;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("runtime_agent_workflow_binding")
public class RuntimeAgentWorkflowBindingEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String agentId;

    private String workflowId;

    private String projectCode;

    private String bindingType;

    private String pageKey;

    private String routePattern;

    private String actionKey;

    private String intentType;

    private Integer priority;

    private Boolean enabled;

    private String guardConfigJson;

    private String metadataJson;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
