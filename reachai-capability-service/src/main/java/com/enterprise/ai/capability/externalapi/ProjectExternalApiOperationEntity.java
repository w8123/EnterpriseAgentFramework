package com.enterprise.ai.capability.externalapi;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("capability_project_external_api_operation")
public class ProjectExternalApiOperationEntity {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long integrationId;
    private Long operationId;
    private LocalDateTime createdAt;
}
