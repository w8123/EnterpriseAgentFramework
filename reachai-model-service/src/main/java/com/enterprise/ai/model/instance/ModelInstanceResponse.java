package com.enterprise.ai.model.instance;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.Map;

@Data
@Builder
public class ModelInstanceResponse {
    private String id;
    private String name;
    private String provider;
    private String modelType;
    private String modelName;
    private String endpointType;
    private String workspaceId;
    private Map<String, Object> credential;
    private Map<String, Object> defaultOptions;
    private Object paramsSchema;
    private String status;
    private String remark;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
