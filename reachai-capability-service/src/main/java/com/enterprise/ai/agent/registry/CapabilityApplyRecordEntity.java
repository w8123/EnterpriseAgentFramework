package com.enterprise.ai.agent.registry;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("capability_apply_record")
public class CapabilityApplyRecordEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long snapshotId;

    private Long diffItemId;

    private String syncId;

    private Long projectId;

    private String projectCode;

    private String qualifiedName;

    /** APPLY / IGNORE / SOFT_DELETE。 */
    private String action;

    /** SUCCESS / FAILED。 */
    private String status;

    private String operator;

    private String message;

    private LocalDateTime createdAt;
}
