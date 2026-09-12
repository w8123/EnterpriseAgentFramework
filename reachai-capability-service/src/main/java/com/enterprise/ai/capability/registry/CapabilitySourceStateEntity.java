package com.enterprise.ai.capability.registry;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.FieldStrategy;
import lombok.Data;
import java.time.LocalDateTime;

/** Latest verified SDK observation; independent of whether its catalog change was accepted. */
@Data
@TableName("capability_source_state")
public class CapabilitySourceStateEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long projectId;
    private String projectCode;
    private String qualifiedName;
    private Long snapshotId;
    private Long diffItemId;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String sourceContractHash;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String acceptedContractHash;
    private String availability;
    private LocalDateTime observedAt;
}
