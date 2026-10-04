package com.enterprise.ai.capability.catalog.httpapi;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** Latest inventory attempt for one project, environment and discovery source. */
@Data
@TableName("capability_http_api_inventory_state")
public class HttpApiInventoryStateEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long projectId;
    private String projectCode;
    private String environment;
    private String sourceKind;
    private String inventoryToken;
    private Boolean supported;
    private Boolean complete;
    private String reason;
    private LocalDateTime observedAt;
}
