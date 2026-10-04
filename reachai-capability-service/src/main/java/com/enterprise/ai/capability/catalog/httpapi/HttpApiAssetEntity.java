package com.enterprise.ai.capability.catalog.httpapi;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** Capability-owned aggregate for one logical HTTP operation. */
@Data
@TableName("capability_http_api_asset")
public class HttpApiAssetEntity {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long projectId;
    private String projectCode;
    private String environment;
    private String identityHash;
    private String qualifiedName;
    private String httpMethod;
    private String routeTemplate;
    private String mappingConditionsJson;
    private String status;
    private String acceptedContractHash;
    private String acceptedContractJson;
    private Long toolDefinitionId;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
