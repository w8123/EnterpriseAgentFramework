package com.enterprise.ai.capability.catalog.businessmethod;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** Immutable acceptance reference. Contract contents remain in the original Registry snapshot. */
@Data
@TableName("capability_business_method_revision")
public class BusinessMethodRevisionEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long assetId;
    private Long snapshotId;
    private Long diffItemId;
    /** Business input/output/behavior fingerprint, independent of an SDK transport address. */
    private String contractHash;
    /** Source execution fingerprint, including the accepted transport, used by existing execution guards. */
    private String invocationHash;
    /** Transport facts are fixed independently so a connection change invalidates a previous proof. */
    private String bindingHash;
    private LocalDateTime acceptedAt;
}
