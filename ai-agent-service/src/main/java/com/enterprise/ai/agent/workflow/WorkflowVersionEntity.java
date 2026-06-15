package com.enterprise.ai.agent.workflow;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("ai_workflow_version")
public class WorkflowVersionEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String workflowId;

    private String version;

    private String snapshotJson;

    private String graphSpecSnapshotJson;

    private String canvasSnapshotJson;

    private Integer rolloutPercent;

    private String status;

    private String publishedBy;

    private LocalDateTime publishedAt;

    private String note;

    private LocalDateTime createdAt;
}
