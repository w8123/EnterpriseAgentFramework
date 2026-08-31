package com.enterprise.ai.model.catalog;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@TableName("model_catalog_sync_run")
public class ModelCatalogSyncRunEntity {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long sourceId;
    private LocalDate businessDate;
    private String triggerType;
    private String status;
    private Integer attemptCount;
    private Integer maxAttempts;
    private LocalDateTime availableAt;
    private String leaseOwner;
    private String leaseToken;
    private LocalDateTime leasedUntil;
    private Integer httpStatus;
    private String contentSha256;
    private Long snapshotId;
    private String analysisStatus;
    private Integer candidateCount;
    private Integer publishedCount;
    private Integer reviewCount;
    private String lastErrorCode;
    private String lastErrorMessage;
    private LocalDateTime startedAt;
    private LocalDateTime completedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
