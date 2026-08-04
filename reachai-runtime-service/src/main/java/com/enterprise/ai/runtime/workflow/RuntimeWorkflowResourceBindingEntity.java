package com.enterprise.ai.runtime.workflow;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("runtime_workflow_resource_binding")
public class RuntimeWorkflowResourceBindingEntity {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String workflowId;
    private Long projectId;
    private String projectCode;
    private String resourceType;
    private String resourceKey;
    private String bindingRole;
    private String status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
