package com.enterprise.ai.runtime.workflow;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("runtime_workflow_draft_submission")
public class RuntimeWorkflowDraftSubmissionEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String workflowId;
    private String requestHash;
    private String sourceType;
    private Long projectId;
    private String projectCode;
    private String taskId;
    private String targetKey;
    private LocalDateTime workflowRevision;
    private LocalDateTime createdAt;
}
