package com.enterprise.ai.agent.registry;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("capability_snapshot")
public class CapabilitySnapshotEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long projectId;

    private String projectCode;

    private String syncId;

    private String source;

    /** PENDING / APPLIED / PARTIAL / IGNORED。 */
    private String status;

    /** SDK 上报原始能力清单 JSON。 */
    private String payloadJson;

    private Integer received;

    private Integer added;

    private Integer changed;

    private Integer unchanged;

    private Integer deleted;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
