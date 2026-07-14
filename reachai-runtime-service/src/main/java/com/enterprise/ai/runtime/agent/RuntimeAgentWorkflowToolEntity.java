package com.enterprise.ai.runtime.agent;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("runtime_agent_workflow_tool")
public class RuntimeAgentWorkflowToolEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String agentId;
    private Long agentConfigVersionId;
    private String workflowId;
    private Long workflowVersionId;
    private String toolName;
    private String descriptionOverride;
    private String inputSchemaOverrideJson;
    private String outputSchemaOverrideJson;
    private String riskLevel;
    private String permissionKey;
    private Boolean readOnly;
    private Boolean enabled;
    private Integer priority;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
