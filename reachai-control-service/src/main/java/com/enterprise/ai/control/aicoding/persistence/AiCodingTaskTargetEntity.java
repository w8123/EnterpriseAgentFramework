package com.enterprise.ai.control.aicoding.persistence;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("control_ai_coding_task_target")
public class AiCodingTaskTargetEntity {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String taskId;
    private String targetType;
    private String targetKey;
    private String targetRole;
    private String accessMode;
    private String snapshotJson;
    private LocalDateTime createdAt;
}
