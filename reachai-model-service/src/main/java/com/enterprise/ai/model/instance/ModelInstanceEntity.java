package com.enterprise.ai.model.instance;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("ai_model_instance")
public class ModelInstanceEntity {

    @TableId(type = IdType.INPUT)
    private String id;

    private String name;

    private String provider;

    private String modelType;

    private String modelName;

    private String endpointType;

    private String workspaceId;

    private String credentialJson;

    private String defaultOptionsJson;

    private String paramsSchemaJson;

    private String status;

    private String remark;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
