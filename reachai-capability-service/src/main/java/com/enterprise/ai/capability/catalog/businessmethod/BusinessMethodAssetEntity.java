package com.enterprise.ai.capability.catalog.businessmethod;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** The Capability-owned identity and current accepted revision of a declared Java operation. */
@Data
@TableName("capability_business_method_asset")
public class BusinessMethodAssetEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long projectId;
    private String projectCode;
    private String methodCode;
    private String qualifiedName;
    /** Stable name used by invocation protocols, owned here rather than by Tool rows. */
    private String invocationName;
    /** Search/display projections of the accepted source; never independently editable contracts. */
    private String title;
    private String description;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private Long acceptedRevisionId;
    private String status;
    private Boolean enabled;
    private String sideEffect;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
