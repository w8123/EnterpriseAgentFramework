package com.enterprise.ai.runtime.agent;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("runtime_agent_config_version")
public class RuntimeAgentConfigVersionEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String agentId;
    private Integer versionNo;
    private String status;
    private String runtimeType;
    private String systemPrompt;
    private String modelInstanceId;
    private Integer maxPlanSteps;
    private Integer maxWorkflowCalls;
    private Integer maxReplans;
    private Integer totalTimeoutMs;
    private Integer workflowTimeoutMs;
    private Integer pageBridgeTimeoutMs;
    private Boolean parallelReadOnly;
    private String policyProfile;
    private String toolCatalogMode;
    private String configJson;
    private String publishedBy;
    private LocalDateTime publishedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
