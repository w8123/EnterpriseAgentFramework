package com.enterprise.ai.runtime.workflow;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** Append-only publication history; activating an old release does not rewrite its publisher. */
@Data
@TableName("runtime_workflow_release_event")
public class RuntimeWorkflowReleaseEventEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String workflowId;
    private String action;
    private Long previousVersionId;
    private Long targetVersionId;
    private String actor;
    private String baseRevision;
    private LocalDateTime occurredAt;
}
