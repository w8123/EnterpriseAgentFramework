package com.enterprise.ai.capability.registry;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.time.LocalDateTime;

/** A request identity survives content-level snapshot deduplication. */
@Data
@TableName("capability_sync_receipt")
public class CapabilitySyncReceiptEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long projectId;
    private String syncId;
    private Long snapshotId;
    private String intakeMode;
    private String contentHash;
    private LocalDateTime createdAt;
}
