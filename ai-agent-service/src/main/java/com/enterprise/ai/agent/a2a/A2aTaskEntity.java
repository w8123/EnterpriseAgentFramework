package com.enterprise.ai.agent.a2a;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("a2a_task")
public class A2aTaskEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String taskId;
    private Long endpointId;
    private String agentKey;
    private String contextId;
    private String userId;
    private String state;
    private String inputMessageJson;
    private String outputTaskJson;
    private String traceId;
    private String errorMessage;
    private LocalDateTime startedAt;
    private LocalDateTime completedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
