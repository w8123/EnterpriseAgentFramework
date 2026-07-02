package com.enterprise.ai.runtime.workflow;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("ai_workflow")
public class RuntimeWorkflowDefinitionEntity {

    @TableId(type = IdType.INPUT)
    private String id;

    private Long projectId;

    private String projectCode;

    private String keySlug;

    private String name;

    private String description;

    private String workflowType;

    private String runtimeType;

    private String graphSpecJson;

    private String canvasJson;

    private String inputSchemaJson;

    private String outputSchemaJson;

    private String defaultModelInstanceId;

    private String defaultResourceConfigJson;

    private String status;

    private String managedBy;

    private String extraJson;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    @TableField(exist = false)
    private Boolean deletable;
}
