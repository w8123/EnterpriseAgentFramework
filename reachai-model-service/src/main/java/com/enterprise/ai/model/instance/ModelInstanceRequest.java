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
    private ModelProtocol protocol;
    private String projectCode;
    /** 连接配置（规范字段），非 credential */
    private Map<String, Object> connection;
    private Map<String, Object> defaultOptions;
    private Object paramsSchema;
    private ModelInstanceStatus status;
    private String remark;
}
