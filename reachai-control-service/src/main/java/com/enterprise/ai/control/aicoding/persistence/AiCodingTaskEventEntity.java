package com.enterprise.ai.control.aicoding.persistence;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("control_ai_coding_task_event")
public class AiCodingTaskEventEntity {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String taskId;
    private String clientEventId;
    private String eventType;
    private String executionStatusAfter;
    private String message;
    private String payloadJson;
    private String actorType;
    private String actorName;
    private LocalDateTime createdAt;
}
