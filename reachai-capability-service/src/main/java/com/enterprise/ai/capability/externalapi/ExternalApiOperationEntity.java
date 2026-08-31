package com.enterprise.ai.capability.externalapi;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("capability_external_api_operation")
public class ExternalApiOperationEntity {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long versionId;
    private String operationKey;
    private String operationId;
    private String title;
    private String description;
    private String httpMethod;
    private String path;
    private String sideEffect;
    private Boolean authRequired;
    private String requestSchemaJson;
    private String responseSchemaJson;
    private String exampleParamsJson;
    private String status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
