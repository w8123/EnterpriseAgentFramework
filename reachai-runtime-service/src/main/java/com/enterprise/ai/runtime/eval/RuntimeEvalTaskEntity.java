package com.enterprise.ai.runtime.eval;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("runtime_eval_task")
public class RuntimeEvalTaskEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String taskType;
    private Long experimentId;
    private Long experimentItemId;
    private String status;
    private Integer priority;
    private Integer attemptCount;
    private Integer maxAttempts;
    private LocalDateTime availableAt;
    private String leaseOwner;
    private String leaseToken;
    private LocalDateTime leasedUntil;
    private String lastErrorCode;
    private String lastErrorMessage;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private LocalDateTime completedAt;
}
