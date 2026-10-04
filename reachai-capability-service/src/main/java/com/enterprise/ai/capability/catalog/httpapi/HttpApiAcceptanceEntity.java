package com.enterprise.ai.capability.catalog.httpapi;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** Immutable evidence for an actor's acceptance of the currently observed API contract. */
@Data
@TableName("capability_http_api_acceptance")
public class HttpApiAcceptanceEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long assetId;
    private String sourceSetRevision;
    private String beforeContractHash;
    private String acceptedContractHash;
    private String acceptedBy;
    private LocalDateTime acceptedAt;
}
