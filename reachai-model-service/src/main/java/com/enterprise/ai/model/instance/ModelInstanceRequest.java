package com.enterprise.ai.model.instance;

import lombok.Data;

import java.util.Map;

@Data
public class ModelInstanceRequest {
    private String id;
    private String name;
    private String provider;
    private ModelType modelType;
    private String modelName;
    private EndpointType endpointType;
    private String workspaceId;
    private Map<String, Object> credential;
    private Map<String, Object> defaultOptions;
    private Object paramsSchema;
    private ModelInstanceStatus status;
    private String remark;
}
