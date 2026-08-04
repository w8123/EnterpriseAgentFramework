package com.enterprise.ai.control.aicoding.persistence;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("control_ai_coding_task_artifact")
public class AiCodingTaskArtifactEntity {

    @TableId(type = IdType.AUTO)
    private Long artifactId;
    private String taskId;
    private String artifactKey;
    private String contractKey;
    private String contractVersion;
    private String contentHash;
    private String contentJson;
    private String processingStatus;
    private String validationMessage;
    private String applicationResultJson;
    private String reportedBy;
    private LocalDateTime createdAt;
    private LocalDateTime validatedAt;
    private LocalDateTime appliedAt;
}
