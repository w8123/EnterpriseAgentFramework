package com.enterprise.ai.control.context;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("control_memory_erasure_domain")
public class MemoryErasureDomainEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long erasureRequestId;
    private String domainCode;
    private String ownerService;
    private String executionMode;
    private String status;
    private String resultCode;
    private Long affectedCount;
    private String evidenceReference;
    private Integer attemptCount;
    private String lastFailureCode;
    private LocalDateTime completedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
