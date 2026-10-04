package com.enterprise.ai.capability.catalog.httpapi;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** Per-binding confirmation in the most recent inventory, separate from the source fact. */
@Data
@TableName("capability_http_api_inventory_member")
public class HttpApiInventoryMemberEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long bindingId;
    private String inventoryToken;
    private LocalDateTime observedAt;
}
