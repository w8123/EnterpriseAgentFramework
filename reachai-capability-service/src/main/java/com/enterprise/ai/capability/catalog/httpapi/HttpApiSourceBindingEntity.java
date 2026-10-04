package com.enterprise.ai.capability.catalog.httpapi;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** One source observation bound to an HTTP API asset; source facts remain independently traceable. */
@Data
@TableName("capability_http_api_source_binding")
public class HttpApiSourceBindingEntity {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long assetId;
    private Long projectId;
    private String projectCode;
    private String environment;
    private String sourceKind;
    private String sourceKey;
    private String sourceLocation;
    private String sourceRevision;
    private String sourceContractHash;
    private String sourceContractJson;
    private String status;
    private LocalDateTime observedAt;
    private LocalDateTime removedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
